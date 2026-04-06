package game;

public class Player {
    private String playerId;
    private int x, y;
    private int score;
    private int hp;
    private boolean isFrozen;
    private boolean hasWeapon;

    public Player(String playerId, int x, int y) {
        this.playerId = playerId;
        this.x = x;
        this.y = y;
        this.score = 0;
        this.hp = 100;
        this.isFrozen = false;
        this.hasWeapon = false;
    }

    public String getPlayerId() { return playerId; }

    public int getX() { return x; }
    public void setX(int x) { this.x = x; }

    public int getY() { return y; }
    public void setY(int y) { this.y = y; }

    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }

    public int getHp() { return hp; }
    public void setHp(int hp) { this.hp = hp; }

    public boolean isFrozen() { return isFrozen; }
    public void setFrozen(boolean frozen) { this.isFrozen = frozen; }

    public boolean hasWeapon() { return hasWeapon; }
    public void setHasWeapon(boolean hasWeapon) { this.hasWeapon = hasWeapon; }


    public void move(String direction) {
        if (isFrozen) return;
        switch (direction) {
            case "UP":    y -= 1; break;
            case "DOWN":  y += 1; break;
            case "LEFT":  x -= 1; break;
            case "RIGHT": x += 1; break;
        }
    }

    public int addScore(int amount){
        score += amount;
        return score;
    }

    public int deductScore(int amount){
        score -= amount;
        return score;
    }

    public void freeze(Player target) {
        if (hasWeapon) {
            target.setFrozen(true);
        }
    }

    public void unfreeze(Player target){
        target.setFrozen(false);
    }

    public void collectItem(String itemType) {
    if (itemType.equals("WEAPON")) {
        hasWeapon = true;
    } else if (itemType.equals("ENERGY")) {
        score += 5;
    }
}

    public boolean isAlive() {
        return hp > 0;
    }

    public void takeDamage(int amount) {
        hp -= amount;
        if (hp < 0) hp = 0;
    }

    public String toString() {
        return playerId + "," + x + "," + y + "," + score + "," + hp + "," + isFrozen + "," + hasWeapon;
    }
}