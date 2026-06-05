package com.virin.visionquiz.util

/*
Damerau-Levenshtein距离算法：

优点：

能够处理多种编辑操作，包括插入、删除、替换和交换字符。
相对于Levenshtein距离算法，具有更高的匹配准确度。
缺点：

相对于Levenshtein距离算法，计算复杂度更高。
当需要处理长字符串时，性能可能会降低。
*/
object DamerauLevenshteinDistance {
    fun computeDamerauLevenshteinDistance(s1: CharSequence, s2: CharSequence): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }
        for (i in 0..len1) {
            dp[i][0] = i
        }
        for (j in 0..len2) {
            dp[0][j] = j
        }
        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] =
                    Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && (s1[i - 1] == s2[j - 2]) && (s1[i - 2] == s2[j - 1])) {
                    dp[i][j] = Math.min(dp[i][j], dp[i - 2][j - 2] + cost)
                }
            }
        }
        return dp[len1][len2]
    }
}

/*
Jaro-Winkler距离算法：

优点：

计算速度相对较快，尤其适合处理较长的字符串。
能够捕捉字符串的相似度，并对相似度进行排序。
相对于Levenshtein距离算法，具有更高的匹配准确度。
缺点：

不能够处理交换字符的情况，只能处理插入、删除和替换字符。
在处理较短字符串时，可能会导致误匹配。
*/
object JaroWinklerDistance {
    private const val JW_COEF = 0.1
    private const val MAX_PREFIX_LENGTH = 4
    fun computeJaroWinklerDistance(
        s1: CharSequence,
        s2: CharSequence
    ): Double {
        val len1 = s1.length
        val len2 = s2.length
        if (len1 == 0 || len2 == 0) {
            return 0.0
        }
        val matchDistance = Math.max(len1, len2) / 2 - 1
        val s1Matched = BooleanArray(len1)
        val s2Matched = BooleanArray(len2)
        var matches = 0
        var transpositions = 0
        for (i in 0 until len1) {
            val start = Math.max(0, i - matchDistance)
            val end = Math.min(i + matchDistance + 1, len2)
            for (j in start until end) {
                if (!s2Matched[j] && s1[i] == s2[j]) {
                    s1Matched[i] = true
                    s2Matched[j] = true
                    matches++
                    break
                }
            }
        }
        if (matches == 0) {
            return 0.0
        }
        var k = 0
        for (i in 0 until len1) {
            if (s1Matched[i]) {
                while (!s2Matched[k]) {
                    k++
                }
                if (s1[i] != s2[k]) {
                    transpositions++
                }
                k++
            }
        }
        val jaro =
            (matches.toDouble() / len1 + matches.toDouble() / len2 + (matches - transpositions).toDouble() / matches) / 3
        var prefixLength = 0
        while (prefixLength < MAX_PREFIX_LENGTH && prefixLength < Math.min(
                len1,
                len2
            ) && s1[prefixLength] == s2[prefixLength]
        ) {
            prefixLength++
        }
        return jaro + prefixLength * JW_COEF * (1 - jaro)
    }
}