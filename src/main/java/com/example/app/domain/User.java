package com.example.app.domain;

public record User(long id, String username, Role role, boolean active, boolean mustChangePassword) {
}
