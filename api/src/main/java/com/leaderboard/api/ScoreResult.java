package com.leaderboard.api;

public record ScoreResult(String username, String mode, int rating, int delta) {}
