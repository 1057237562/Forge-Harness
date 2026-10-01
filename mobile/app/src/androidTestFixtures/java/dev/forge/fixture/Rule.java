package dev.forge.fixture;
public interface Rule {
    int apply(int value);
    default int evaluate(int value) { return apply(value) + 1; }
    static Rule identity() { return value -> value; }
}
