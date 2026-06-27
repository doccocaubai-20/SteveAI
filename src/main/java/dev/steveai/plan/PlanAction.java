package dev.steveai.plan;

public record PlanAction(
    String type,
    String text,
    String material,
    String command,
    int ticks,
    int radius,
    int amount,
    int x,
    int y,
    int z,
    int x1,
    int y1,
    int z1,
    int x2,
    int y2,
    int z2
) {
}