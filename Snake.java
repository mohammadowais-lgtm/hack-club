import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Properties;
import java.util.Random;

public class Snake {
    private static final int W = 36;
    private static final int H = 18;
    private static final char ESC = 27;
    private static final Random RNG = new Random();
    private static final Path SAVE = Paths.get(
            System.getProperty("user.home"), ".snake-evolution.properties");

    private static final String RESET = "\033[0m";
    private static final String GREEN = "\033[92m";
    private static final String DIM = "\033[2m";
    private static final String CYAN = "\033[96m";
    private static final String RED = "\033[91m";
    private static final String YELLOW = "\033[93m";
    private static final String CLEAR = "\033[2J\033[H";

    private static Properties meta = new Properties();
    private static boolean ansi = true;

    private static final class Point {
        final int x, y;
        Point(int x, int y) { this.x = x; this.y = y; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Point)) return false;
            Point p = (Point) o;
            return x == p.x && y == p.y;
        }
        @Override public int hashCode() { return 31 * x + y; }
    }

    private enum Mutation {
        LONGER("Longer Launch", "Start each run with +2 segments", 3),
        SECOND_CHANCE("Second Chance", "Survive one wall/self collision per run", 5),
        QUICK_LEARNER("Quick Learner", "Earn +1 evolution XP per food", 4),
        STAR_MAP("Star Map", "Food is worth +1 score", 3);

        final String title, description;
        final int cost;
        Mutation(String title, String description, int cost) {
            this.title = title; this.description = description; this.cost = cost;
        }
    }

    private static int deaths, xp, best, mutationMask;
    private static int score, foodEaten, runLength;
    private static boolean secondChanceUsed;
    private static Deque<Point> snake;
    private static Point food;
    private static int dx, dy;
    private static boolean running, gameOver;
    private static String message = "Every run leaves you stronger.";

    public static void main(String[] args) throws Exception {
        loadMeta();
        boolean raw = enterTerminalMode();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> restoreTerminal(raw)));
        try {
            titleScreen();
            while (true) {
                String choice = readKeyBlocking();
                if (choice.equals("q")) break;
                if (choice.equals("m")) { mutationScreen(); titleScreen(); continue; }
                if (choice.equals("r") || choice.equals("\n") || choice.equals(" ")) {
                    playRun();
                    if (!running) break;
                    deathScreen();
                }
            }
        } finally {
            restoreTerminal(raw);
            System.out.println("\nThanks for playing. Keep evolving.");
        }
    }

    private static void titleScreen() {
        clear();
        System.out.println(GREEN + "  S N A K E :  D I E .  G E T  S T R O N G E R ." + RESET);
        System.out.println(CYAN + "  A terminal roguelike about learning from failure." + RESET);
        System.out.println();
        System.out.println("  Deaths: " + deaths + "   |   XP: " + xp + "   |   Best Score: " + best);
        System.out.println("  Mutations Unlocked: " + mutationCount() + "/" + Mutation.values().length);
        System.out.println();
        System.out.println(YELLOW + "  [R / Space] Start New Run" + RESET);
        System.out.println(YELLOW + "  [M]         Mutations / Upgrades Shop" + RESET);
        System.out.println(YELLOW + "  [Q]         Quit" + RESET);
        System.out.println();
        System.out.println(DIM + "  Status: " + message + RESET);
    }

    private static void mutationScreen() throws Exception {
        while (true) {
            clear();
            System.out.println(CYAN + "  E V O L U T I O N   L A B" + RESET);
            System.out.println("  Available XP: " + xp + "\n");

            Mutation[] vals = Mutation.values();
            for (int i = 0; i < vals.length; i++) {
                Mutation m = vals[i];
                boolean unlocked = has(m);
                String status = unlocked ? GREEN + "[UNLOCKED]" : (xp >= m.cost ? YELLOW + "[AFFORDABLE]" : DIM + "[LOCKED]");
                System.out.println("  " + (i + 1) + ". " + m.title + " (" + m.cost + " XP) " + status + RESET);
                System.out.println("     " + DIM + m.description + RESET);
            }

            System.out.println("\n  Enter 1-4 to buy; B to go back.");
            String k = readKeyBlocking();
            if (k.equals("b") || k.equals("q") || k.equals("\n")) return;
            if (k.length() == 1 && k.charAt(0) >= '1' && k.charAt(0) <= '4') {
                Mutation m = Mutation.values()[k.charAt(0) - '1'];
                if (has(m)) message = "Already evolved: " + m.title;
                else if (xp < m.cost) message = "Not enough XP for " + m.title;
                else {
                    xp -= m.cost;
                    mutationMask |= (1 << m.ordinal());
                    message = "Mutation unlocked: " + m.title;
                    saveMeta();
                }
            }
        }
    }

    private static void playRun() throws Exception {
        snake = new ArrayDeque<>();
        int startX = W / 2;
        int startY = H / 2;
        int initialLen = has(Mutation.LONGER) ? 5 : 3;
        for (int i = 0; i < initialLen; i++) {
            snake.addLast(new Point(startX - i, startY));
        }
        dx = 1;
        dy = 0;
        score = 0;
        foodEaten = 0;
        runLength = initialLen;
        secondChanceUsed = false;
        running = true;
        gameOver = false;
        spawnFood();

        while (running) {
            String input = readKeyNonBlocking();
            if (!input.isEmpty()) {
                if (input.equals("q")) { running = false; break; }
                if (input.equals("p")) { pauseScreen(); }
                if (input.equals("UP") && dy == 0) { dx = 0; dy = -1; }
                if (input.equals("DOWN") && dy == 0) { dx = 0; dy = 1; }
                if (input.equals("LEFT") && dx == 0) { dx = -1; dy = 0; }
                if (input.equals("RIGHT") && dx == 0) { dx = 1; dy = 0; }
            }

            updateGame();
            renderGame();
            // Adjust speed here (higher number = slower snake)
            Thread.sleep(120);
        }
    }

    private static void updateGame() {
        Point head = snake.peekFirst();
        Point next = new Point(head.x + dx, head.y + dy);

        boolean collision = next.x < 0 || next.x >= W || next.y < 0 || next.y >= H || snake.contains(next);

        if (collision) {
            if (has(Mutation.SECOND_CHANCE) && !secondChanceUsed) {
                secondChanceUsed = true;
                message = "Second Chance triggered! Collision ignored.";
                return;
            }
            gameOver = true;
            running = false;
            return;
        }

        snake.addFirst(next);
        if (next.equals(food)) {
            foodEaten++;
            score += has(Mutation.STAR_MAP) ? 2 : 1;
            int xpGain = 1 + (has(Mutation.QUICK_LEARNER) ? 1 : 0);
            xp += xpGain;
            spawnFood();
        } else {
            snake.removeLast();
        }
    }

    private static void spawnFood() {
        while (true) {
            int fx = RNG.nextInt(W);
            int fy = RNG.nextInt(H);
            Point p = new Point(fx, fy);
            if (!snake.contains(p)) {
                food = p;
                break;
            }
        }
    }

    private static void renderGame() {
        clear();
        StringBuilder sb = new StringBuilder();
        sb.append(GREEN).append("  Score: ").append(score).append("  XP Earned This Run: ").append(foodEaten).append(RESET).append("\n");

        char[][] grid = new char[H][W];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                grid[y][x] = ' ';
            }
        }

        grid[food.y][food.x] = 'o';

        boolean headCheck = true;
        for (Point p : snake) {
            grid[p.y][p.x] = headCheck ? 'O' : '*';
            headCheck = false;
        }

        sb.append("+");
        for (int x = 0; x < W; x++) sb.append("-");
        sb.append("+\n");

        for (int y = 0; y < H; y++) {
            sb.append("|");
            for (int x = 0; x < W; x++) {
                char c = grid[y][x];
                if (c == 'O' || c == '*') sb.append(GREEN).append(c).append(RESET);
                else if (c == 'o') sb.append(RED).append(c).append(RESET);
                else sb.append(c);
            }
            sb.append("|\n");
        }

        sb.append("+");
        for (int x = 0; x < W; x++) sb.append("-");
        sb.append("+\n");
        sb.append(DIM).append("Controls: Arrow Keys | P: Pause | Q: Quit").append(RESET);

        System.out.print(sb.toString());
        System.out.flush();
    }

    private static void deathScreen() {
        deaths++;
        if (score > best) {
            best = score;
            message = "New high score achieved!";
        } else {
            message = "You died. Every run makes you stronger.";
        }
        saveMeta();

        clear();
        System.out.println(RED + "  G A M E   O V E R" + RESET);
        System.out.println("  Final Score: " + score);
        System.out.println("  Food Eaten: " + foodEaten);
        System.out.println("\n  Press R to retry, M for mutations, or Q to quit.");

        try {
            while (true) {
                String k = readKeyBlocking();
                if (k.equals("r") || k.equals("\n") || k.equals(" ")) break;
                if (k.equals("m")) { mutationScreen(); break; }
                if (k.equals("q")) { running = false; break; }
            }
        } catch (Exception ignored) {}
    }

    private static void pauseScreen() throws Exception {
        clear();
        System.out.println(YELLOW + "  PAUSED" + RESET + "\n  Press any key to resume.");
        readKeyBlocking();
    }

    private static boolean has(Mutation m) { return (mutationMask & (1 << m.ordinal())) != 0; }
    private static int mutationCount() {
        int n = 0;
        for (Mutation m : Mutation.values()) if (has(m)) n++;
        return n;
    }

    private static void loadMeta() {
        if (!Files.exists(SAVE)) return;
        try {
            meta.load(Files.newInputStream(SAVE));
            deaths = Integer.parseInt(meta.getProperty("deaths", "0"));
            xp = Integer.parseInt(meta.getProperty("xp", "0"));
            best = Integer.parseInt(meta.getProperty("best", "0"));
            mutationMask = Integer.parseInt(meta.getProperty("mutations", "0"));
        } catch (Exception e) {
            System.err.println("Could not read save file; starting fresh.");
        }
    }

    private static void saveMeta() {
        meta.setProperty("deaths", Integer.toString(deaths));
        meta.setProperty("xp", Integer.toString(xp));
        meta.setProperty("best", Integer.toString(best));
        meta.setProperty("mutations", Integer.toString(mutationMask));
        try {
            meta.store(Files.newOutputStream(SAVE), "Snake: Die. Get Stronger. persistent evolution");
        } catch (IOException e) {
            message = "Warning: couldn't save progress: " + e.getMessage();
        }
    }

    private static void clear() {
        System.out.print(ansi ? CLEAR : "\n\n\n");
        System.out.flush();
    }

    private static boolean enterTerminalMode() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            ansi = false;
            System.out.println("This first build targets Linux/macOS terminals. On Windows, use WSL.");
            return false;
        }
        try {
            Process p = new ProcessBuilder("sh", "-c", "stty -g < /dev/tty").start();
            String state = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (!state.isEmpty()) {
                Files.writeString(Paths.get("/tmp/snake-stty-state-" + ProcessHandle.current().pid()), state);
                new ProcessBuilder("sh", "-c", "stty -echo -icanon min 0 time 0 < /dev/tty").inheritIO().start().waitFor();
                return true;
            }
        } catch (Exception ignored) { }
        System.out.println("Warning: couldn't enable raw terminal input. Run from a real terminal, not an IDE console.");
        return false;
    }

    private static void restoreTerminal(boolean raw) {
        if (!raw) return;
        try {
            Path stateFile = Paths.get("/tmp/snake-stty-state-" + ProcessHandle.current().pid());
            if (Files.exists(stateFile)) {
                String state = Files.readString(stateFile).trim().replace("'", "");
                new ProcessBuilder("sh", "-c", "stty '" + state + "' < /dev/tty").inheritIO().start().waitFor();
                Files.deleteIfExists(stateFile);
            } else {
                new ProcessBuilder("sh", "-c", "stty sane < /dev/tty").inheritIO().start().waitFor();
            }
        } catch (Exception ignored) { }
        System.out.print(RESET + "\033[?25h");
        System.out.flush();
    }

    private static String readKeyNonBlocking() {
        try {
            if (System.in.available() <= 0) return "";
            int c = System.in.read();
            if (c == ESC && System.in.available() > 0) {
                int c2 = System.in.read();
                if (c2 == '[' && System.in.available() > 0) {
                    int c3 = System.in.read();
                    if (c3 == 'A') return "UP";
                    if (c3 == 'B') return "DOWN";
                    if (c3 == 'C') return "RIGHT";
                    if (c3 == 'D') return "LEFT";
                }
                return "";
            }
            return Character.toString((char)c).toLowerCase();
        } catch (IOException e) { return ""; }
    }

    private static String readKeyBlocking() throws Exception {
        while (true) {
            String k = readKeyNonBlocking();
            if (!k.isEmpty()) return k;
            Thread.sleep(25);
        }
    }
}
