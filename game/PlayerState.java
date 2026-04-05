package game;

/**
 * PlayerState - All mutable state for one player.
 *
 * Freeze and speed boost removed to match GameClient.
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
        return id + "(" + name + ") pos=(" + x + "," + y + ") score=" + score;
    }
}
