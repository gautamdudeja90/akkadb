
## In-Memory Key-Value Store

### Prerequisites

1. **Java Development Kit (JDK)**: JDK 11 or above.

2. **Scala**
   - Install it by running `brew install scala` if you're using Homebrew.

3. **SBT (Scala Build Tool)**: Version 1.9.7
    - Install it by running `brew install sbt` if you're using Homebrew.

### Versions used:
-  Scala version: 3.3.1
-  JDK version: 11.0.12
-  Akka Actor version: 2.6.17

### Setup

1. **Clone the Repository**:
    ```bash
    git clone https://your-repository-link.git
    ```

2. **Navigate to the Project Directory**:
    ```bash
    cd your-project-directory
    ```

3. **Compile the Project**:
    ```bash
    sbt compile
    ```

### Run

Run the main class `DatastoreServer` to start the server. You can do this in your IDE, or by running the following command in your terminal:

```bash
sbt "runMain DatastoreServer"
```

go to another terminal and run the following command to connect to the server:

```bash
 telnet localhost 11210
```

```text
Trying ::1...
Connected to localhost.
Escape character is '^]'.
start
{"status":"Ok", "action":"START"}
put k1 v1
{"status":"Ok", "action":"PUT"}
commit
{"status":"OK", "action":"COMMIT"}
start
{"status":"Ok", "action":"START"}
put k2 v2
{"status":"Ok", "action":"PUT"}
commit
{"status":"OK", "action":"COMMIT"}
start
{"status":"Ok", "action":"START"}
shpw
{"status":"FAILED", "action":"UNEXPECTED ERROR! OR NO ACTIVE TRANSACTION"}
show
{"status":"Ok", "action":"SHOW ALL", "tmpStore":{}, "dataStore":{k1:v1(version:1), k2:v2(version:1)}}
put k1 v2
{"status":"Ok", "action":"PUT"}
commit
{"status":"OK", "action":"COMMIT"}
show
{"status":"FAILED", "action":"UNEXPECTED ERROR! OR NO ACTIVE TRANSACTION"}
start
{"status":"Ok", "action":"START"}
show
{"status":"Ok", "action":"SHOW ALL", "tmpStore":{}, "dataStore":{k1:v2(version:2), k2:v2(version:1)}}
show
{"status":"Ok", "action":"SHOW ALL", "tmpStore":{}, "dataStore":{k1:v2(version:2), k2:v2(version:1)}}
put k1 v3
{"status":"Ok", "action":"PUT"}
commit
{"status":"OK", "action":"COMMIT"}
start
{"status":"Ok", "action":"START"}
show
{"status":"Ok", "action":"SHOW ALL", "tmpStore":{}, "dataStore":{k1:v3(version:3), k2:v2(version:1)}}
put k1 v4
{"status":"Ok", "action":"PUT"}
commit
{"status":"OK", "action":"COMMIT"}
start 
{"status":"Ok", "action":"START"}
show
{"status":"Ok", "action":"SHOW ALL", "tmpStore":{}, "dataStore":{k1:v4(version:4), k2:v2(version:1)}}
show 
{"status":"Ok", "action":"SHOW ALL", "tmpStore":{}, "dataStore":{k1:v4(version:4), k2:v2(version:1)}}
put k2 v3
{"status":"Ok", "action":"PUT"}
show
{"status":"Ok", "action":"SHOW ALL", "tmpStore":{k2:v3(version:1)}, "dataStore":{k1:v4(version:4), k2:v2(version:1)}}
```

### Features

- Thread-safe, in-memory data storage.
- CRUD operations: PUT, GET, DELETE.
- Transaction support: START, COMMIT, ROLLBACK.
- Scalable and fault-tolerant.

### Overview


#### Assumptions 

- **Single-Node Architecture**: Runs on a single node; doesn't handle distributed data storage or failover.
- **In-Memory Storage**: Data will be lost if the server is stopped.
- **No Authentication**: No security layer to authenticate users or clients.
- **Thread-Safe Operations**: Thread safety is ensured within each actor.
- **Blocking I/O**: Uses blocking I/O for client communication.
- **No Data Expiry**: Key-value pairs don't expire.
- **String-based Keys and Values**: Assumes keys and values are strings.
- **Immediate Consistency**: Aims for immediate consistency within a single actor.
- **Sequential Versioning**: Uses simple long integers for versioning.
- **No Query Support**: Supports only basic CRUD operations.
- **Eventually Consistent**: Doesn't compress data.
- **No eviction strategy**: It can use managed by client itself, for now db would be as big as memory allows.

# Overview

The system consists of an Akka-based server that provides an in-memory key-value store.
The main components are DatastoreActor for handling the data and ClientActor for processing client requests. The server listens on a specific port for client connections and spawns a new ClientActor for each connection.

The system consists of an Akka-based server that provides an in-memory key-value store.
The main components are DatastoreActor for handling the data and ClientActor for processing client requests. The server listens on a specific port for client connections and spawns a new ClientActor for each connection.

##### DatastoreActor
This actor maintains an in-memory HashMap (dataStore) for the key-value pairs. It handles the following operations:
- CommitTransaction: Validates and commits changes to the main datastore.
- Get: Retrieves the value for a given key..


##### ClientActor
This actor interacts with the DatastoreActor and handles client-side operations. It uses temporary data structures (tmpStore and tmpDeleteSet) to keep track of changes during a transaction.START: Initializes a transaction.
-  PUT: Adds/Updates a key-value pair.
-  GET: Fetches a value based on the key.
-  DELETE: Removes a key-value pair.
-  COMMIT: Commits the transaction.
-  ROLLBACK: Rolls back any changes made during the transaction.
-  SHOW : Show current state of transaction and cache datastore

#### DeadLetterListener
This actor listens for dead letters in the Akka system and logs them.

#### Running System end to end
1. Server Initialization: Run DatastoreServer.main() to start the Akka actor system.
2. Client Connection: Connect a client to the server using a TCP client like nc on port 11210.

### Design

#### Single Node Architecture & Atomicity

In this implementation, I leverage Akka actors to create a thread-safe, in-memory key-value store. Each actor in Akka processes one message at a time, effectively making the code within the actor's `receive` method atomic concerning that actor's state.

#### Transactional Behavior

It simulates a transactional system through operations like `StartTransaction`, `CommitTransaction`, and `RollbackTransaction`. These operations are atomic and are coordinated via messages. Since each actor processes messages one at a time, it ensures atomicity and isolates each transaction, even when they are executed concurrently.

#### Optimistic Locking & Versioning

The key-value pairs in the store are versioned. This versioning facilitates optimistic locking, a mechanism to handle concurrent updates. Before a transaction is committed, the system checks the version of the data to ensure that it hasn't been modified by another transaction. If the version numbers match, the transaction proceeds; otherwise, it's rolled back.

#### Data Consistency

The `DatastoreActor` serves as the single point of truth. All data operations pass through this actor, ensuring consistency and atomicity across transactions.

#### Read-Modify-Write Cycle

The actor reads the current version of the data, modifies it, and writes it back only if the version remains unchanged. This optimistic locking mechanism is essential for maintaining consistency across concurrent transactions.

#### Conflict Detection

Before a transaction is committed, version numbers are compared. Matching version numbers indicate that the data has not been altered by another transaction, allowing the commit to proceed. Otherwise, the transaction is rolled back, preventing inconsistent updates.

#### Isolation

Each transaction works on a snapshot of the data, isolating them from one another. This isolation is crucial for maintaining consistency and atomicity across concurrent transactions.

### Scalability & Future Extensions

This architecture can be extended to a distributed setting using Akka's cluster and sharding capabilities. Multiple `DatastoreActor`s can be run on different nodes, accessible via a router. A sharding key can distribute the data across various nodes, enabling horizontal scalability.

By adopting this architecture, we ensure that the system is both performant and maintains a high level of data consistency, making it suitable for applications requiring an in-memory, transactional key-value store.
