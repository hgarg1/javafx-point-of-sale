package com.example.app.domain;

public record Category(long id, String name) {

    @Override
    public String toString() {
        return name;
    }
}
