package com.example.app.domain;

/** An amount grouped by tender: what was taken, refunded or still refundable on one tender, as it was named then. */
public record TenderTotal(String tender, String name, long cents) {
}
