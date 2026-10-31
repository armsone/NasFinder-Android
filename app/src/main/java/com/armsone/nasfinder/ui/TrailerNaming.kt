package com.armsone.nasfinder.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 새로 만드는 저장·공유 파일명과 자동 기본 제목: `yyMMdd-HHmmss-예고편` / `yyMMdd-HHmmss-뮤비` (기기 현지 시각). */
internal fun trailerTimestampName(
    music: Boolean,
    epochMillis: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String = DateTimeFormatter.ofPattern("yyMMdd-HHmmss", Locale.ROOT).withZone(zone)
    .format(Instant.ofEpochMilli(epochMillis)) + if (music) "-뮤비" else "-예고편"
