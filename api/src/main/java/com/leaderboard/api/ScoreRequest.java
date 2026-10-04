package com.leaderboard.api;

/** Send either an absolute {@code rating} or a relative {@code delta}. */
public record ScoreRequest(String username, String mode, Integer rating, Integer delta, String source) {}
