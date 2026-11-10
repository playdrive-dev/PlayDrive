package app.playdrive.engine

/**
 * 운전 판단 임계값을 모두 이곳에 모은다. 튜닝은 이 기본값만 바꾸면 된다.
 * 단위: 속도 m/s, 가속도 m/s², 시간 ms, 각도 도(°).
 */
data class DrivingThresholds(
    // --- 속도 평활화·가속도 ---
    /** 속도 이동평균 창. 10Hz 기준 3샘플. 한두 샘플짜리 튐을 흡수한다. */
    val speedSmoothingWindowMs: Long = 300,
    /** 가속도 = (현재 평활 속도 − 이 시간 전 평활 속도) / 경과시간. */
    val accelWindowMs: Long = 500,

    // --- 급가속·급제동 (0.3g ≈ 2.94 m/s²) ---
    val harshAccelMps2: Float = 2.94f,
    val harshBrakeMps2: Float = 2.94f,
    /** 같은 종류의 급 이벤트가 연달아 터지지 않게 막는 시간. */
    val harshCooldownMs: Long = 2_000,

    // --- 부드러운 가감속 ---
    val smoothMinMps2: Float = 0.5f,
    /** 이 시간 이상 같은 방향으로 부드럽게 가감속하면 이벤트. */
    val smoothMinDurationMs: Long = 1_000,

    // --- 정속 ---
    val cruiseMinSpeedMps: Float = 2.0f,
    val cruiseMaxAbsAccelMps2: Float = 0.3f,
    /** 정속 종료(히스테리시스): 이보다 큰 가감속이면 정속 끝. */
    val cruiseExitAbsAccelMps2: Float = 0.6f,
    val cruiseMinDurationMs: Long = 5_000,

    // --- 정차·출발 ---
    val stoppedSpeedMps: Float = 0.3f,
    /** 신호 대기 판단: 속도 0이 2초 이상(브레이크다운 기준). */
    val stoppedMinDurationMs: Long = 2_000,
    val departSpeedMps: Float = 1.0f,

    // --- 차선 변경(방향지시등) ---
    val laneChangeMaxSignalMs: Long = 10_000,
    val laneChangeMinSpeedMps: Float = 5.0f,

    // --- 급조향 ---
    val harshSteerRateDegPerSec: Float = 150f,
    val harshSteerMinSpeedMps: Float = 5.0f,
    /** 조향각은 바뀔 때만 오므로, 변화율 계산 시 경과시간을 이 범위로 제한한다. */
    val steerRateMinDtMs: Long = 100,
    val steerRateMaxDtMs: Long = 500,

    // --- 하모니 지수(0~100) ---
    val harmonyInitial: Float = 50f,
    /** 급 이벤트 후 이 시간이 지나야 다시 오르기 시작한다. */
    val harmonyCalmAfterHarshMs: Long = 5_000,
    val harmonyGainPerSecMoving: Float = 0.8f,
    val harmonyGainPerSecCruise: Float = 1.5f,
    val harmonyPenaltyHarshAccel: Float = 10f,
    val harmonyPenaltyHarshBrake: Float = 15f,
    val harmonyPenaltyHarshSteer: Float = 8f,
)
