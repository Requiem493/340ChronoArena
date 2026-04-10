package game;

import java.util.UUID;

/**
 * Item - A collectible on the map.
 *
 * Only WEAPON and ENERGY remains — SPEED removed to match GameClient.
 *
 * @author Requiem493, help from claude.ai
 */
public class Item {

    public enum Type {
        ENERGY,
        FREEZE_RAY
    }

    public final String id;
    public final Type type;
    public int x, y;

    private static final int PICKUP_RADIUS = 25;

    public Item(Type type, int x, int y) {
        this.id = type.name().charAt(0) + UUID.randomUUID().toString().substring(0, 6);
        this.type = type;
        this.x = x;
        this.y = y;
    }

    public boolean overlaps(int px, int py) {
        return Math.hypot(px - x, py - y) <= PICKUP_RADIUS;
    }

    @Override
    public String toString() {
        return type + "@(" + x + "," + y + ")";
    }
}
