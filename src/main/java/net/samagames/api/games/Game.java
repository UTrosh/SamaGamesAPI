package net.samagames.api.games;

import net.samagames.api.SamaGamesAPI;
import net.samagames.api.games.themachine.ICoherenceMachine;
import net.samagames.api.games.themachine.items.PlayerTracker;
import net.samagames.api.games.themachine.messages.templates.EarningMessageTemplate;
import net.samagames.tools.Titles;
import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.InvocationTargetException;
import java.util.*;


/**
 * This class represents the game executed by the plugin using this, if applicable.
 *
 * @param <GAMEPLAYER> The class used to store players' data during the game — not to be
 *                    confused with the players data store used to persist data over time.
 *                    This class must be a subclass of {@link GamePlayer}. If you don't need
 *                    to store specific data about this player, use the {@link GamePlayer}
 *                    class here.
 */
public class Game<GAMEPLAYER extends GamePlayer>
{
    protected final IGameManager gameManager;

    protected final String gameCodeName;
    protected final String gameName;
    protected final String gameDescription;
    protected final Class<GAMEPLAYER> gamePlayerClass;
    protected final HashMap<UUID, GAMEPLAYER> gamePlayers;
    protected final PlayerTracker playerTracker;
    protected BukkitTask beginTimer;
    protected BeginTimer beginObj;

    protected ICoherenceMachine coherenceMachine;
    protected Status status;
    protected long startTime = -1;

    /**
     * With this constructor, the players will not see a description of the game when
     * logging in. Only the game's name in a {@code /title}.
     *
     * @param gameCodeName The code name of the game, given by an administrator.
     * @param gameName The friendly name of the game.
     * @param gamePlayerClass The class of your custom {@link GamePlayer} object, the same
     *                        as the {@link GAMEPLAYER} class. Use {@code GamePlayer.class}
     *                        if you are not using a custom class.
     */
    public Game(String gameCodeName, String gameName, Class<GAMEPLAYER> gamePlayerClass)
    {
        this(gameCodeName, gameName, "", gamePlayerClass);
    }

    /**
     * @param gameCodeName The code name of the game, given by an administrator.
     * @param gameName The friendly name of the game.
     * @param gameDescription A short description of the game, displayed to the players
     *                        when they join the game through a /title.
     * @param gamePlayerClass The class of your custom {@link GamePlayer} object, the same
     *                        as the {@link GAMEPLAYER} class. Use {@code GamePlayer.class}
     *                        if you are not using a custom class.
     */
    public Game(String gameCodeName, String gameName, String gameDescription, Class<GAMEPLAYER> gamePlayerClass)
    {
        this.gameManager = SamaGamesAPI.get().getGameManager();

        this.gameCodeName = gameCodeName.toLowerCase();
        this.gameName = gameName;
        this.gameDescription = gameDescription;
        this.gamePlayerClass = gamePlayerClass;
        this.gamePlayers = new HashMap<>();
        this.playerTracker = new PlayerTracker(this);

        this.status = Status.WAITING_FOR_PLAYERS;
    }

    /**
     * Starts the game.
     *
     * Override this command to execute something when the game starts.
     *
     * You need to call the {@code super} method at the beginning of your own one.
     */
    public void startGame()
    {
        this.startTime = System.currentTimeMillis();
        this.beginTimer.cancel();
        this.setStatus(Status.IN_GAME);

        this.coherenceMachine.getMessageManager().writeGameStart();
    }

    /**
     * Override this method to execute something when the game was just registered.
     *
     * You need to call the {@code super} method at the beginning of your own one.
     */
    public void handlePostRegistration()
    {
        this.coherenceMachine = this.gameManager.getCoherenceMachine();
        this.beginObj = new BeginTimer(this);
        this.beginTimer = Bukkit.getScheduler().runTaskTimerAsynchronously(SamaGamesAPI.get().getPlugin(), beginObj, 20L, 20L);
    }

    /**
     * Override this to execute something when a normal player joins the game at the
     * beginning of it (this will neo be called for reconnections).
     * Prefer the use of {@link GamePlayer#handleLogin(boolean)}.
     *
     * You need to call the {@code super} method at the beginning of your own one.
     *
     * @param player The player who logged in.
     */
    public void handleLogin(Player player)
    {
        try
        {
            GAMEPLAYER gamePlayerObject = this.gamePlayerClass.getConstructor(Player.class).newInstance(player);
            gamePlayerObject.handleLogin(false);

            this.gamePlayers.put(player.getUniqueId(), gamePlayerObject);

            Titles.sendTitle(player, 20, 20 * 3, 20, ChatColor.DARK_AQUA + "" + ChatColor.BOLD + this.gameName, ChatColor.AQUA + this.gameDescription);
        }
        catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e)
        {
            e.printStackTrace();
        }

        this.coherenceMachine.getMessageManager().writePlayerJoinToAll(player);
    }

    /**
     * Override this to execute something when a moderator joins the game.
     *
     * You need to call the {@code super} method at the beginning of your own one.
     *
     * @param player The player who logged in.
     */
    public void handleModeratorLogin(Player player)
    {
        for(GamePlayer gamePlayer : this.gamePlayers.values())
        {
            Player p = gamePlayer.getPlayerIfOnline();
            if (p != null)
                p.hidePlayer(player);
        }

        player.setGameMode(GameMode.SPECTATOR);
        player.sendMessage(ChatColor.GREEN + "Vous êtes invisibles aux yeux de tous, attention à vos actions !");
    }

    /**
     * Override this to execute something when a player logout at any time.
     * Prefer the use of {@link GamePlayer#handleLogout()}.
     *
     * You need to call the {@code super} method at the beginning of your own one.
     *
     * @param player The player who logged out.
     */
    public void handleLogout(Player player)
    {
        if(this.status == Status.FINISHED)
            return;

        if(this.gamePlayers.containsKey(player.getUniqueId()))
        {
            if(!this.gamePlayers.get(player.getUniqueId()).isSpectator())
            {
                if(this.gameManager.isReconnectAllowed(player) && this.status == Status.IN_GAME)
                {
                    this.gameManager.getCoherenceMachine().getMessageManager().writePlayerDisconnected(player, (this.gameManager.getMaxReconnectTime() * 60000) - (int) (System.currentTimeMillis() - startTime));
                }
                else
                {
                    this.gameManager.getCoherenceMachine().getMessageManager().writePlayerQuited(player);
                }
            }

            this.gamePlayers.get(player.getUniqueId()).handleLogout();

            if(this.status != Status.IN_GAME)
                this.gamePlayers.remove(player.getUniqueId());

            this.gameManager.refreshArena();
        }
    }

    /**
     * Override this to execute something when a player reconnects into the game.
     * Prefer the use of {@link GamePlayer#handleLogin(boolean)}.
     *
     * You need to call the {@code super} method at the beginning of your own one.
     *
     * @param player The player who just logged in back.
     */
    public void handleReconnect(Player player)
    {
        this.gameManager.getCoherenceMachine().getMessageManager().writePlayerReconnected(player);

        GamePlayer gamePlayer = this.gamePlayers.get(player.getUniqueId());

        if (gamePlayer != null && (gamePlayer.isSpectator() && !gamePlayer.isModerator()))
            gamePlayer.setSpectator();

        this.gamePlayers.get(player.getUniqueId()).handleLogin(true);
    }

    /**
     * Override this to execute something when a disconnected player can no longer join
     * the game.
     *
     * You need to call the {@code super} method at the beginning of your own one.
     *
     * @param player The player who can no longer rejoin the game.
     * @param silent Display a message
     */
    public void handleReconnectTimeOut(Player player, boolean silent)
    {
        this.setSpectator(player);

        if (!silent)
            this.gameManager.getCoherenceMachine().getMessageManager().writePlayerReconnectTimeOut(player);
    }

    /**
     * Call this method when the game is finished.
     *
     * If for some reasons you want to override this method, you will need to call the
     * {@code super} method at the beginning of your own one.
     */
    public void handleGameEnd()
    {
        this.setStatus(Status.FINISHED);

        Bukkit.getScheduler().runTaskLater(SamaGamesAPI.get().getPlugin(), () ->
        {
            this.gamePlayers.keySet().stream().filter(playerUUID -> Bukkit.getPlayer(playerUUID) != null).forEach(playerUUID ->
            {
                EarningMessageTemplate earningMessageTemplate = this.coherenceMachine.getTemplateManager().getEarningMessageTemplate();
                earningMessageTemplate.execute(Bukkit.getPlayer(playerUUID), this.getPlayer(playerUUID).getCoins(), this.getPlayer(playerUUID).getStars());
                this.increaseStat(playerUUID, "played-games", 1);
            });
        }, 20L * 3);

        Bukkit.getScheduler().runTaskLater(SamaGamesAPI.get().getPlugin(), () ->
        {
            for (Player player : Bukkit.getOnlinePlayers())
                this.gameManager.kickPlayer(player, null);
        }, 20L * 10);

        Bukkit.getScheduler().runTaskLater(SamaGamesAPI.get().getPlugin(), () ->
        {
            SamaGamesAPI.get().getStatsManager(this.gameCodeName).finish();
            Bukkit.shutdown();
        }, 20L * 15);
    }

    /**
     * Credits coins to the given player. Works for offline players.
     *
     * Use {@link GamePlayer#addCoins(int, String)} instead, if possible.
     *
     * @param player The receiver of the coins.
     * @param coins The amount of coins.
     * @param reason The displayed reason of this credit.
     */
    public void addCoins(Player player, int coins, String reason)
    {
        if(this.gamePlayers.containsKey(player.getUniqueId()))
            this.gamePlayers.get(player.getUniqueId()).addCoins(coins, reason);
        else
            SamaGamesAPI.get().getPlayerManager().getPlayerData(player.getUniqueId()).creditCoins(coins, reason, true);
    }

    /**
     * Credits stars to the given player. Works for offline players.
     *
     * Use {@link GamePlayer#addStars(int, String)} instead, if possible.
     *
     * @param player The receiver of the stars.
     * @param stars The amount of stars.
     * @param reason The displayed reason of this credit.
     */
    public void addStars(Player player, int stars, String reason)
    {
        if(this.gamePlayers.containsKey(player.getUniqueId()))
            this.gamePlayers.get(player.getUniqueId()).addStars(stars, reason);
        else
            SamaGamesAPI.get().getPlayerManager().getPlayerData(player.getUniqueId()).creditStars(stars, reason, false);
    }

    /**
     * Increases the named statistic of the given player by {@code count}.
     *
     * @param uuid The incremented player's UUID.
     * @param statName The incremented statistic's name.
     * @param count The amount by which this statistic is incremented.
     */
    public void increaseStat(UUID uuid, String statName, int count)
    {
        SamaGamesAPI.get().getStatsManager(this.gameCodeName).increase(uuid, statName, count);
    }

    /**
     * Marks a player as spectator.
     *
     * @param player The player to mark as spectator.
     *
     * @see GamePlayer#setSpectator() The method of the GamePlayer object (to be used if possible).
     */
    public void setSpectator(Player player)
    {
        this.gamePlayers.get(player.getUniqueId()).setSpectator();
    }

    /**
     * Returns this game's code name.
     *
     * @return The code.
     */
    public String getGameCodeName()
    {
        return this.gameCodeName;
    }

    /**
     * Returns this game's name.
     *
     * @return The name.
     */
    public String getGameName()
    {
        return this.gameName;
    }

    /**
     * Returns this game's current status.
     *
     * @return The status.
     */
    public Status getStatus()
    {
        return this.status;
    }

    /**
     * Returns the CoherenceMachine instance
     *
     * @return The instance
     */
    public ICoherenceMachine getCoherenceMachine()
    {
        return this.coherenceMachine;
    }

    /**
     * Set the game's status. You'll never have to use this normally.
     *
     * @param status The new status.
     */
    public void setStatus(Status status)
    {
        this.status = status;
        this.gameManager.refreshArena();
    }

    /**
     * Returns the internal representation of the given player.
     *
     * @param player The player's UUID.
     * @return The instance of {@link GAMEPLAYER} representing this player.
     */
    public GAMEPLAYER getPlayer(UUID player)
    {
        if(this.gamePlayers.containsKey(player))
            return this.gamePlayers.get(player);
        else
            return null;
    }

    /**
     * Return a map ({@link UUID} → {@link GamePlayer}) of the currently in-game
     * players (i.e. players neither dead nor moderators).
     *
     * This map contains offline players who are still able to login, if the reconnection is
     * allowed.
     *
     * @return The map containing the in-game players.
     */
    public Map<UUID, GAMEPLAYER> getInGamePlayers()
    {
        Map<UUID, GAMEPLAYER> inGamePlayers = new HashMap<>();

        for(UUID key : this.gamePlayers.keySet())
        {
            final GAMEPLAYER gPlayer = this.gamePlayers.get(key);

            if (!gPlayer.isSpectator())
                inGamePlayers.put(key, gPlayer);
        }

        return inGamePlayers;
    }

    /**
     * Return a map ({@link UUID} → {@link GamePlayer}) of the currently spectating players
     * (moderators included).
     *
     * This map does not contains offline players who are still able to login, if the
     * reconnection is allowed.
     *
     * @return The map containing the spectating players.
     */
    public Map<UUID, GAMEPLAYER> getSpectatorPlayers()
    {
        Map<UUID, GAMEPLAYER> spectators = new HashMap<>();

        for(UUID key : this.gamePlayers.keySet())
        {
            final GAMEPLAYER gPlayer = this.gamePlayers.get(key);

            if(gPlayer.isSpectator())
                spectators.put(key, gPlayer);
        }

        return spectators;
    }

    /**
     * Return a map ({@link UUID} → {@link GamePlayer}) of the currently spectating players
     * (moderators <strong>excluded</strong>).
     *
     * This map does not contains offline players who are still able to login, if the
     * reconnection is allowed.
     *
     * @return The map containing the spectating players.
     */
    public Map<UUID, GAMEPLAYER> getVisibleSpectatingPlayers()
    {
        HashMap<UUID, GAMEPLAYER> spectators = new HashMap<>();

        for(UUID key : this.gamePlayers.keySet())
        {
            final GAMEPLAYER gPlayer = this.gamePlayers.get(key);

            if(gPlayer.isSpectator() && !gPlayer.isModerator())
                spectators.put(key, gPlayer);
        }

        return spectators;
    }

    /**
     * Return a read-only map ({@link UUID} → {@link GamePlayer}) of the registered players.
     *
     * @return All registered game players.
     */
    public Map<UUID, GAMEPLAYER> getRegisteredGamePlayers()
    {
        return Collections.unmodifiableMap(gamePlayers);
    }

    /**
     * Returns the timer used to count down the time when the game is not started.
     *
     * @return The timer.
     */
    public BukkitTask getBeginTimer()
    {
        return beginTimer;
    }

    /**
     * Returns the player tracker to use in this game. Internal use.
     *
     * @return The player tracker compass.
     */
    public PlayerTracker getPlayerTracker()
    {
        return this.playerTracker;
    }

    /**
     * Returns the amount of in-game (alive) players.
     *
     * Calling this is mostly the same as calling {@code getInGamePlayers().size()}, but
     * with better performances.
     *
     * @return The amount of in-game (alive) players.
     */
    public int getConnectedPlayers()
    {
        int i = 0;

        for(GamePlayer player : gamePlayers.values())
            if(!player.isSpectator())
                i++;

        return i;
    }

    /**
     * Checks if the specified player is registered and have a stored {@link GAMEPLAYER}
     * representation.
     *
     * @param player The player.
     * @return {@code true} if registered.
     */
    public boolean hasPlayer(Player player)
    {
        return gamePlayers.containsKey(player.getUniqueId());
    }

    /**
     * Called when a player try to login into the game, this will check if the player
     * is able to join or not.
     *
     * The default implementation allows anyone to join or rejoin (if the reconnection is
     * allowed); override this to change this behavior.
     *
     * @param player The player trying to login.
     * @param reconnect {@code true} if the player is reconnecting.
     *
     * @return A {@link Pair} instance, where:
     * <ul>
     *     <li>
     *         the left value is a {@link Boolean}, saying if the player is allowed to join
     *         ({@code true}) or not ({@code false});
     *     </li>
     *     <li>
     *         the right value is the error message displayed, if the connection
     *         is refused; ignored else.
     *     </li>
     * </ul>
     */
    public Pair<Boolean, String> canJoinGame(UUID player, boolean reconnect)
    {
        return Pair.of(true, "");
    }

    /**
     * Called when a party try to join the server; this will check if the whole party can login
     * or not.
     *
     * The default implementation allows any party to join (assuming the server is large enough
     * to accept all players, of course).
     *
     * @param partyMembers A {@link Set} containing the {@link UUID} of the party's members.
     *
     * @return A {@link Pair} instance, where:
     * <ul>
     *     <li>
     *         the left value is a {@link Boolean}, saying if the party is allowed to join
     *         ({@code true}) or not ({@code false});
     *     </li>
     *     <li>
     *         the right value is the error message displayed, if the connection
     *         is refused; ignored else.
     *     </li>
     * </ul>
     */
    public Pair<Boolean, String> canPartyJoinGame(Set<UUID> partyMembers)
    {
        return Pair.of(true, "");
    }

    /**
     * Checks if the given player is spectating or not.
     *
     * @param player The player.
     * @return {@code true} if spectating (moderators included).
     */
    public boolean isSpectator(Player player)
    {
        return gamePlayers.get(player.getUniqueId()).isSpectator();
    }

    /**
     * Checks if the game is started, i.e. all status after the beginning of the game
     * (started, finished, rebooting).
     *
     * @return {@code true} if the game is started.
     */
    public boolean isGameStarted()
    {
        return this.status == Status.IN_GAME || this.status == Status.FINISHED || this.status == Status.REBOOTING;
    }

    public long getStartTime()
    {
        return startTime;
    }
}
