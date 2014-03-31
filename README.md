IA Trader Coursework
====================

IA Trader Coursework by James Barnett, Gen Nam-Lam, and Ryan Tyrrell

## Running the Agent

Before running the agent, start the tac and info servers

```
java -jar tacserver.jar
java -jar infoserver.jar
```

Open localhost:8080, and register a new user. Use the agent name and password.

Run the agent using `java -jar tacagent.jar`

### Agent Rules
- Buy outbound flights at beginning
- Determine price of hotels
  -
- If ever all hotels are bought, buy return flights
