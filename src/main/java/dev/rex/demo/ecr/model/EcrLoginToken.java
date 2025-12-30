package dev.rex.demo.ecr.model;

public record EcrLoginToken(
        String registry,
        String username,
        String password
) {
}
