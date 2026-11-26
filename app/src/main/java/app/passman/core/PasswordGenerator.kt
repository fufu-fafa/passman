package app.passman.core

import java.security.SecureRandom

/** FR-11: random passwords from a secure random generator, 8–64 characters, default 20. */
object PasswordGenerator {
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 64
    const val DEFAULT_LENGTH = 20

    const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    const val DIGITS = "0123456789"
    const val SYMBOLS = "!@#$%^&*()-_=+[]{};:,.?/~"

    private val defaultRandom = SecureRandom()

    /**
     * Each selected class appears at least once; the rest are drawn uniformly from all selected
     * characters. With no class selected, letters are used.
     */
    fun generate(
        length: Int = DEFAULT_LENGTH,
        letters: Boolean = true,
        digits: Boolean = true,
        symbols: Boolean = true,
        random: SecureRandom = defaultRandom,
    ): String {
        val len = length.coerceIn(MIN_LENGTH, MAX_LENGTH)
        val classes = buildList {
            if (letters) add(LETTERS)
            if (digits) add(DIGITS)
            if (symbols) add(SYMBOLS)
        }.ifEmpty { listOf(LETTERS) }
        val all = classes.joinToString("")
        val chars = CharArray(len)
        classes.forEachIndexed { i, set -> chars[i] = set[random.nextInt(set.length)] }
        for (i in classes.size until len) chars[i] = all[random.nextInt(all.length)]
        for (i in len - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val t = chars[i]; chars[i] = chars[j]; chars[j] = t
        }
        return String(chars).also { chars.fill('\u0000') }
    }

    const val CONSONANTS = "bcdfghjklmnpqrstvwxz"
    const val VOWELS = "aeiouy"
    const val GROUPS = 3
    const val GROUP_LENGTH = 6
    const val MEMORABLE_LENGTH = GROUPS * GROUP_LENGTH + GROUPS - 1

    /**
     * Apple-style strong password, e.g. `vadpyb-Gofzi1-xydjuk`: three pronounceable groups of six
     * (consonant-vowel-consonant-consonant-vowel-consonant) joined by hyphens, with exactly one
     * uppercase letter and one digit at the start or end of a group. About 71 bits of entropy.
     */
    fun generateMemorable(random: SecureRandom = defaultRandom): String {
        fun pick(set: String) = set[random.nextInt(set.length)]
        val groups = Array(GROUPS) {
            CharArray(GROUP_LENGTH) { i -> pick(if (i == 1 || i == 4) VOWELS else CONSONANTS) }
        }
        val digitGroup = random.nextInt(GROUPS)
        val digitPos = if (random.nextBoolean()) 0 else GROUP_LENGTH - 1
        groups[digitGroup][digitPos] = pick(DIGITS)
        while (true) {
            val g = random.nextInt(GROUPS)
            val p = random.nextInt(GROUP_LENGTH)
            if (g == digitGroup && p == digitPos) continue
            groups[g][p] = groups[g][p].uppercaseChar()
            break
        }
        return groups.joinToString("-") { String(it) }.also { groups.forEach { it.fill('\u0000') } }
    }
}
