package app.passman

import app.passman.core.Base32
import app.passman.core.CorruptVaultException
import app.passman.core.Entry
import app.passman.core.EntryCodec
import app.passman.core.EntryQuery
import app.passman.core.KdfParams
import app.passman.core.PasswordGenerator
import app.passman.core.SortOrder
import app.passman.core.Totp
import app.passman.core.TotpConfig
import app.passman.core.UnlockThrottle
import app.passman.core.VaultCrypto
import app.passman.core.WebsiteUrl
import app.passman.core.WrongPasswordException
import app.passman.data.VaultFileStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CoreTest {
    // Small Argon2 cost so tests run fast; the format stores whatever params were used.
    private val fast = KdfParams(memoryKiB = 1024, iterations = 1, parallelism = 1)
    private val pw = "correct horse battery".toCharArray()

    private val tricky = Entry(
        id = "id-1",
        title = "Ünïcödé 🔐 Bank",
        userName = "  user@example.com  ",
        password = " Pa ss\tWORD with spaces ",
        code = "JBSWY3DPEHPK3PXP",
        website = "bank.example.com",
        notes = "line one\nline two\r\n\nemoji 🎉 and 中文\n007",
        modifiedAt = 1_764_000_000_123L,
    )

    @Test fun `codec round-trips every field exactly`() {
        val list = listOf(tricky, tricky.copy(id = "id-2", title = "0042", code = "", notes = ""))
        assertEquals(list, EntryCodec.decode(EntryCodec.encode(list)))
    }

    @Test fun `vault opens with the right password and round-trips entries`() {
        val (file, keys) = VaultCrypto.create(pw, listOf(tricky), fast)
        keys.destroy()
        val opened = VaultCrypto.open(file, pw.copyOf())
        assertEquals(listOf(tricky), opened.entries)
        assertEquals(fast, opened.keys.kdfParams)
    }

    @Test fun `reseal uses a fresh nonce and stays openable`() {
        val (file1, keys) = VaultCrypto.create(pw, listOf(tricky), fast)
        val file2 = VaultCrypto.seal(keys, listOf(tricky))
        assertFalse(file1.contentEquals(file2))
        assertEquals(listOf(tricky), VaultCrypto.open(file2, pw).entries)
    }

    @Test(expected = WrongPasswordException::class)
    fun `wrong password is rejected`() {
        val (file, _) = VaultCrypto.create(pw, listOf(tricky), fast)
        VaultCrypto.open(file, "wrong password!!".toCharArray())
    }

    @Test fun `every single-byte change to the file is rejected`() {
        val (file, _) = VaultCrypto.create(pw, listOf(tricky), fast)
        for (i in file.indices) {
            val bad = file.copyOf()
            bad[i] = (bad[i].toInt() xor 0x01).toByte()
            try {
                VaultCrypto.open(bad, pw)
                fail("tampered byte $i was accepted")
            } catch (e: WrongPasswordException) {
            } catch (e: CorruptVaultException) {
            }
        }
    }

    @Test fun `truncated and garbage files are rejected`() {
        val (file, _) = VaultCrypto.create(pw, listOf(tricky), fast)
        for (len in listOf(0, 3, 20, file.size - 1)) {
            try {
                VaultCrypto.open(file.copyOf(len), pw)
                fail("truncated to $len accepted")
            } catch (e: CorruptVaultException) {
            } catch (e: WrongPasswordException) {
            }
        }
        try {
            VaultCrypto.open("hello world, not a vault".toByteArray(), pw)
            fail()
        } catch (e: CorruptVaultException) {
        }
    }

    @Test fun `no plaintext field appears in the vault file`() {
        val marker = tricky.copy(
            title = "TITLEMARKER123", userName = "USERMARKER123", password = "PASSMARKER123",
            code = "JBSWY3DPEHPK3PXP", website = "sitemarker123.example", notes = "NOTEMARKER123",
        )
        val (file, _) = VaultCrypto.create(pw, listOf(marker), fast)
        val asText = String(file, Charsets.ISO_8859_1)
        for (needle in listOf("TITLEMARKER123", "USERMARKER123", "PASSMARKER123", "JBSWY3DPEHPK3PXP", "sitemarker123", "NOTEMARKER123")) {
            assertFalse(needle, asText.contains(needle))
        }
    }

    @Test fun `destroyed keys refuse to seal`() {
        val (_, keys) = VaultCrypto.create(pw, emptyList(), fast)
        keys.destroy()
        assertTrue(keys.isDestroyed)
        try {
            VaultCrypto.seal(keys, emptyList())
            fail()
        } catch (e: IllegalStateException) {
        }
    }

    @Test fun `crafted header with huge memory cost is refused before deriving`() {
        val (file, _) = VaultCrypto.create(pw, emptyList(), fast)
        val bad = file.copyOf()
        // memoryKiB lives at offset 5..8
        bad[5] = 0x7f; bad[6] = -1; bad[7] = -1; bad[8] = -1
        try {
            VaultCrypto.open(bad, pw)
            fail()
        } catch (e: CorruptVaultException) {
        }
    }

    // ---- Open with the raw vault key (fingerprint unlock) ----

    @Test fun `openWithKey round-trips with the exported key`() {
        val (file, keys) = VaultCrypto.create(pw, listOf(tricky), fast)
        val raw = keys.exportKeyCopy()
        val opened = VaultCrypto.openWithKey(file, raw)
        assertEquals(listOf(tricky), opened.entries)
        // The opened keys own a copy: zeroing ours doesn't break them.
        raw.fill(0)
        assertEquals(listOf(tricky), VaultCrypto.open(VaultCrypto.seal(opened.keys, opened.entries), pw).entries)
    }

    @Test fun `openWithKey with the wrong key is a corrupt vault`() {
        val (file, _) = VaultCrypto.create(pw, listOf(tricky), fast)
        val (_, other) = VaultCrypto.create(pw, emptyList(), fast)
        for (key in listOf(other.exportKeyCopy(), ByteArray(32), ByteArray(16))) {
            try {
                VaultCrypto.openWithKey(file, key)
                fail()
            } catch (e: CorruptVaultException) {
            }
        }
    }

    @Test fun `openWithKey rejects every single-byte change`() {
        val (file, keys) = VaultCrypto.create(pw, listOf(tricky), fast)
        val raw = keys.exportKeyCopy()
        for (i in file.indices) {
            val bad = file.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            try {
                VaultCrypto.openWithKey(bad, raw)
                fail("byte $i accepted")
            } catch (e: CorruptVaultException) {
            }
        }
    }

    @Test fun `exportKeyCopy refuses after destroy`() {
        val (_, keys) = VaultCrypto.create(pw, emptyList(), fast)
        keys.destroy()
        try {
            keys.exportKeyCopy()
            fail()
        } catch (e: IllegalStateException) {
        }
    }

    @Test fun `memory cost above 256 MiB is refused and 256 MiB is accepted`() {
        val (file, _) = VaultCrypto.create(pw, emptyList(), fast)
        fun withMemory(kib: Int) = file.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(5, kib) }
        try {
            VaultCrypto.readParams(withMemory(256 * 1024 + 1))
            fail()
        } catch (e: CorruptVaultException) {
        }
        assertEquals(256 * 1024, VaultCrypto.readParams(withMemory(256 * 1024)).memoryKiB)
    }

    @Test fun `argon2id matches the reference implementation test vector`() {
        // phc-winner-argon2 test.c: Argon2id v1.3, t=2, m=2^16 KiB, p=1, "password", "somesalt".
        val key = VaultCrypto.deriveKek("password".toCharArray(), "somesalt".toByteArray(), KdfParams(65536, 2, 1))
        assertEquals("09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7", key.joinToString("") { "%02x".format(it) })
    }

    @Test fun `atomic store keeps the old vault when a temp file is left behind`() {
        val dir = Files.createTempDirectory("pm").toFile()
        val store = VaultFileStore(dir)
        store.write(byteArrayOf(1, 2, 3))
        File(dir, "vault.pmv.tmp").writeBytes(byteArrayOf(9, 9)) // a crash mid-write
        val reopened = VaultFileStore(dir)
        assertArrayEquals(byteArrayOf(1, 2, 3), reopened.read())
        assertFalse(File(dir, "vault.pmv.tmp").exists())
        reopened.write(byteArrayOf(4))
        assertArrayEquals(byteArrayOf(4), reopened.read())
        dir.deleteRecursively()
    }

    // ---- TOTP: RFC 6238 Appendix B vectors (8 digits) ----

    @Test fun `totp matches RFC 6238 vectors`() {
        val sha1 = TotpConfig("12345678901234567890".toByteArray(), digits = 8)
        val sha256 = TotpConfig("12345678901234567890123456789012".toByteArray(), 8, 30, TotpConfig.Algorithm.SHA256)
        val sha512 = TotpConfig("1234567890123456789012345678901234567890123456789012345678901234".toByteArray(), 8, 30, TotpConfig.Algorithm.SHA512)
        val cases = listOf(
            59L to Triple("94287082", "46119246", "90693936"),
            1111111109L to Triple("07081804", "68084774", "25091201"),
            1111111111L to Triple("14050471", "67062674", "99943326"),
            1234567890L to Triple("89005924", "91819424", "93441116"),
            2000000000L to Triple("69279037", "90698825", "38618901"),
            20000000000L to Triple("65353130", "77737706", "47863826"),
        )
        for ((t, expected) in cases) {
            assertEquals(expected.first, Totp.code(sha1, t * 1000))
            assertEquals(expected.second, Totp.code(sha256, t * 1000))
            assertEquals(expected.third, Totp.code(sha512, t * 1000))
        }
    }

    @Test fun `totp parses secrets and otpauth uris`() {
        val bare = Totp.parse("jbsw y3dp-ehpk 3pxp")!!
        assertEquals(6, bare.digits)
        val uri = Totp.parse("otpauth://totp/Example:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example&digits=8&period=60&algorithm=SHA256")!!
        assertEquals(8, uri.digits)
        assertEquals(60, uri.periodSeconds)
        assertEquals(TotpConfig.Algorithm.SHA256, uri.algorithm)
        assertNull(Totp.parse("not base32 !!"))
        assertNull(Totp.parse("ABC")) // too short to be a real secret
        assertNull(Totp.parse("otpauth://hotp/x?secret=JBSWY3DPEHPK3PXP"))
        assertNull(Totp.parse(""))
        // Leading zeros are kept.
        val code = Totp.code(bare, 0)
        assertEquals(6, code.length)
        assertEquals(30, Totp.secondsRemaining(bare, 0))
        assertEquals(1, Totp.secondsRemaining(bare, 29_999))
    }

    @Test fun `base32 decodes RFC 4648 vectors`() {
        assertArrayEquals("foobar".toByteArray(), Base32.decode("MZXW6YTBOI======"))
        assertArrayEquals("f".toByteArray(), Base32.decode("MY======"))
    }

    // ---- Generator ----

    @Test fun `generator honours length and character sets`() {
        repeat(200) {
            val p = PasswordGenerator.generate(20)
            assertEquals(20, p.length)
            assertTrue(p.any { it in PasswordGenerator.LETTERS })
            assertTrue(p.any { it in PasswordGenerator.DIGITS })
            assertTrue(p.any { it in PasswordGenerator.SYMBOLS })
        }
        assertTrue(PasswordGenerator.generate(8, letters = false, digits = true, symbols = false).all { it.isDigit() })
        assertEquals(8, PasswordGenerator.generate(2).length)
        assertEquals(64, PasswordGenerator.generate(500).length)
        assertEquals(30, PasswordGenerator.generate(30, false, false, false).count { it.isLetter() })
    }

    // ---- Website ----

    @Test fun `website rules`() {
        assertEquals("https://example.com", WebsiteUrl.toOpenable("example.com"))
        assertEquals("https://example.com:8443/a?b=c", WebsiteUrl.toOpenable(" example.com:8443/a?b=c "))
        assertEquals("http://intranet.local", WebsiteUrl.toOpenable("http://intranet.local"))
        assertEquals("HTTPS://Example.com", WebsiteUrl.toOpenable("HTTPS://Example.com"))
        assertNull(WebsiteUrl.toOpenable("javascript:alert(1)"))
        assertNull(WebsiteUrl.toOpenable("file:///etc/passwd"))
        assertNull(WebsiteUrl.toOpenable("ftp://example.com"))
        assertNull(WebsiteUrl.toOpenable("intent://x#Intent;end"))
        assertNull(WebsiteUrl.toOpenable("mailto:a@b.c"))
        assertNull(WebsiteUrl.toOpenable(""))
        assertNull(WebsiteUrl.toOpenable("exa mple.com"))
    }

    // ---- Search and sort ----

    @Test fun `search covers title user website notes but never password or code`() {
        val e = Entry("1", "GitHub", "octo", "secretpw", "JBSWY3DPEHPK3PXP", "github.com", "work account", 1)
        assertTrue(EntryQuery.matches(e, "git"))
        assertTrue(EntryQuery.matches(e, "OCTO"))
        assertTrue(EntryQuery.matches(e, "hub.com"))
        assertTrue(EntryQuery.matches(e, "work"))
        assertFalse(EntryQuery.matches(e, "secretpw"))
        assertFalse(EntryQuery.matches(e, "JBSW"))
    }

    @Test fun `sort by title is case-insensitive and by modified is newest first`() {
        val a = Entry("1", "banana", "", "", "", "", "", 3)
        val b = Entry("2", "Apple", "", "", "", "", "", 1)
        val c = Entry("3", "cherry", "", "", "", "", "", 2)
        assertEquals(listOf("Apple", "banana", "cherry"), EntryQuery.sort(listOf(a, b, c), SortOrder.TITLE).map { it.title })
        assertEquals(listOf("banana", "cherry", "Apple"), EntryQuery.sort(listOf(a, b, c), SortOrder.MODIFIED).map { it.title })
    }

    @Test fun `search over 1000 entries is fast`() {
        val list = (1..1000).map { Entry("$it", "Site $it", "user$it@mail.com", "pw", "", "site$it.com", "note $it ".repeat(20), it.toLong()) }
        val start = System.nanoTime()
        repeat(10) { EntryQuery.filterAndSort(list, "site 5", SortOrder.TITLE) }
        val perQueryMs = (System.nanoTime() - start) / 10 / 1_000_000
        assertTrue("search took $perQueryMs ms", perQueryMs < 200)
    }

    // ---- Throttle ----

    @Test fun `unlock delays grow and cap`() {
        assertEquals(0, UnlockThrottle.delayMillisAfter(2))
        assertEquals(5_000, UnlockThrottle.delayMillisAfter(3))
        assertEquals(300_000, UnlockThrottle.delayMillisAfter(50))
        assertEquals(2_000, UnlockThrottle.remainingMillis(3, lastFailureAt = 10_000, now = 13_000))
        assertEquals(0, UnlockThrottle.remainingMillis(3, lastFailureAt = 10_000, now = 20_000))
        assertNotNull(UnlockThrottle.remainingMillis(3, lastFailureAt = 10_000, now = 0))
    }

    @Test fun `a reboot restarts the wait instead of skipping it`() {
        // Same boot: measured from the recorded failure.
        assertEquals(10_000, UnlockThrottle.failureTimeFor(10_000, lastFailureBoot = 7, now = 12_000, boot = 7))
        // New boot (uptime clock restarted): the wait starts again from now.
        assertEquals(500, UnlockThrottle.failureTimeFor(10_000, lastFailureBoot = 7, now = 500, boot = 8))
        assertEquals(50_000, UnlockThrottle.failureTimeFor(10_000, lastFailureBoot = 7, now = 50_000, boot = 8))
        // Missing, legacy or impossible values also restart it.
        assertEquals(500, UnlockThrottle.failureTimeFor(-1, lastFailureBoot = Int.MIN_VALUE, now = 500, boot = 3))
        assertEquals(500, UnlockThrottle.failureTimeFor(9_000, lastFailureBoot = -1, now = 500, boot = -1))
        // So after a reboot the full delay applies.
        val from = UnlockThrottle.failureTimeFor(10_000, 7, now = 500, boot = 8)
        assertEquals(300_000, UnlockThrottle.remainingMillis(9, from, now = 500))
    }
}
