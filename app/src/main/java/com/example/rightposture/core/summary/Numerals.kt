package com.example.rightposture.core.summary

/**
 * Where the numbers are in a generated summary, so the screen can pick them out.
 *
 * Uses the **same** regex `checkSummary` validates against, and that sharing is the whole point.
 * Rule 1 of the validator rejects any digit run not in [SummaryFacts.allowedNumbers], so in a
 * summary that was accepted every numeral is a measurement we computed - which means emphasising
 * them is a claim the pipeline can actually back.
 *
 * A second, hand-written regex here would eventually disagree with the validator's, and the first
 * symptom would be a highlighted number nobody ever checked. Same expression, one source.
 */
fun numeralRanges(text: String): List<IntRange> =
    NUMBER_RUN.findAll(text).map { it.range }.toList()
