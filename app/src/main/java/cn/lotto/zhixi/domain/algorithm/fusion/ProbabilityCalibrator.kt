package cn.lotto.zhixi.domain.algorithm.fusion

object ProbabilityCalibrator {

    /**
     * 对概率数组进行安全校准：
     * 1. 确保无 NaN、无 Infinity，且均非负。
     * 2. 归一化使总和等于 1.0。
     * 3. 混合均匀分布基准 P_final = (1 - lambda) * P_uniform + lambda * P_model
     */
    fun calibrateBlueProbabilities(rawProbabilities: DoubleArray, lambda: Double = 0.25): DoubleArray {
        val size = 16
        val result = DoubleArray(size + 1)
        val uniformP = 1.0 / size.toDouble()

        var sum = 0.0
        for (i in 1..size) {
            val raw = rawProbabilities.getOrNull(i) ?: 0.0
            val safeRaw = if (raw.isNaN() || raw.isInfinite() || raw < 0.0) 0.0 else raw
            result[i] = safeRaw
            sum += safeRaw
        }

        // 若总和非法或全 0，重置为均匀概率
        if (sum <= 0.0 || sum.isNaN() || sum.isInfinite()) {
            for (i in 1..size) result[i] = uniformP
            return result
        }

        // 归一化 P_model
        for (i in 1..size) {
            result[i] = result[i] / sum
        }

        // 概率混淆校准
        val clampedLambda = lambda.coerceIn(0.0, 0.5)
        for (i in 1..size) {
            result[i] = (1.0 - clampedLambda) * uniformP + clampedLambda * result[i]
        }

        return result
    }
}
