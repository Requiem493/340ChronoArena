# 340ChronoArena
## Class: 
#### CSC-340

## Team Name: 
#### ICANNT

## Team Members: 
#### Aiden Agas, Aditi Baghel, Akshaj Illa

## Our Project
#### We created a Chrono Arena game using java swing for the front end and socket programming for the communication between the game and client. 

## Game Properties File
#### server.ip=YOUR_SERVER_IP_HERE
#### server.tcp.port=8000
#### server.udp.port=9000

## Architecture
### Server
####
#### Main Thread (TCP 8000 and UDP 9000) (spawns) --> ClientHandler Thread 1, ClientHandler Thread 2, ClientHandler Thread 3, ClientHandler Thread n (TCP per client)
#### UDP Receiver Thread (receives movement packets) (all share) --> GameState (authoritative state) (conatins) --> Action Queue (ConcurrentLinkedQueue) and Players, Zones, Items
####
### Client
####
#### GUI Thread (Swing EDT) (spawns) --> TCP Listener thread (receives STATE broadcasts)
#### UDP Sender (sends movement packets) (shared) --> LocalGameState (parsed snapshot) (renders) --> ArenaGUI (repain every 16ms)

## Getting The Jar Files
#### The JAR files you will need are mainserver.jar for the main server and gui.jar for playing the game. If it doesn't run well on VS Code, you can run it in your computer terminal.

## Getting Into The Game
### As The Server
#### You will need to enter this in your terminal
#### java -jar mainserver.jar < IP > < TCP port number > < UDP port number > < number of seconds for the round >
### As The Player
#### You will need to enter this in your terminal
#### java -jar gui.jar 

## How To Play
### Start Screen
#### To play this game, you will need to know the server's IP address to sign in. When you open the game, the game screen will show two text boxes for your name and the IP address that you'll need to fill and two buttons, one to start the game and the other to show you the keys how to play the game. The IP address is treated like a password so no one can see it. If you don't enter both fields the screen will ask you to fill in the sections.
### Playing The Game
#### Once you are in the game, if you are in the server, on the top, there will be a timer for how many seconds is left in the round, the number of players, the players that are currently playing, and two buttons for Kill Player and Status. If you are the player, there will be a timer and score you you have on the top for the round. For the screen, there will be a light blue grid, showing different zones, yellow circles as the energy points, blue squares for you to freeze a player, and the players itself. To move around, W/Up Arrow is to move up, A/Left Arrow is to move left, S/Down Arrow is to move down, and D/Right Arrow is to move right. F key is to freeze a player. When a player enters a zone, they capture it and get points with decaying gain over time. When the game is over, the results will show in your terminal.
### Scoring
#### Energy coin give 15 points
#### Zone capture points are awarded over time (decaying)
#### Freeze ray temporarily disables opponent for 3 seconds
#### Grace timer is 5 seconds after leaving a zone before losing control

## Team Contributions
#### Aiden Agas: GUI, Game Screen gameplay
#### Aditi Baghel: GUI, Start Screen, ArenaGUI Connection
#### Akshaj Illa: Backend, Server Logic, Some Games Logic(zones)

## Java Version
#### Java 17 or higher