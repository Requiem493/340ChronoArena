package game;

/**
 * PlayerState - All mutable state for one player.
 *
 * @author Requiem493, help from claude.ai
 */
public class PlayerState {

    // Identity
    public final String id;
    public final String name;

    // Position (pixels on the 800x600 map)
    public int x;
    public int y;

    // Score
    public int score = 0;

    // Temporary status effects / inventory
    public boolean frozen = false;
    public boolean hasWeapon = false;
    public long frozenUntilMs = 0;

    // Kill-switch flag: ClientHandler polls this and closes socket if true
    public volatile boolean killed = false;

    public PlayerState(String id, String name, int startX, int startY) {
        this.id = id;
        this.name = name;
        this.x = startX;
        this.y = startY;
    }

    @Override
    public String toString() {
        return id + "(" + name + ") pos=(" + x + "," + y + ") score=" + score
                + " frozen=" + frozen + " weapon=" + hasWeapon;
    }
}
