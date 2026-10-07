package com.example.app.domain;

public record Supplier(long id, String name, String contact) {

    @Override
    public String toString() {
        return name;
    }
}
