// raw("", lang: "kotlin", block: true)

= Flang 2.0

== Examples

=== Hello World
```rust
@Event
fn Join(event: PlayerJoinEvent) {
  for player in Selections.allPlayers() {
    player.sendMessage(s"Hello World");
  }

  // Potential Syntax Sugar
  Selections.allPlayers().forEach(it.sendMessage(s"Hello World"));
}
```

=== Defining an Event (Stdlib)
```rust
interface CancellableEvent {
  fn cancel(var self) {
    emit `game_action "CancelEvent"`;
  }

  fn uncancel(var self) {
    emit `game_action "UncancelEvent"`;
  }
}

@PlayerEventProvider("TakeDamage")
object PlayerTakeDamageEvent;

impl CancellableEvent for PlayerTakeDamageEvent;

impl PlayerTakeDamageEvent {
  fn victim(val self) -> Player {
    return gval("UUID", .Victim) as Player;
  }
}
```
=== Using it
```rust
@Event
fn takeDamage(var event: PlayerTakeDamageEvent) {
  if (event.victim().name().equals("zBinFinn")) {
    event.cancel();
  }
}
```

#pagebreak()

= Mutability
```rust
var x = 5; // Mutable
val y = 6; // Immutable
```

There will need to be consideration on how struct members are handled, will they always be cloned on intialization? Otherwise local variables could escape their context and that'd break things.

Maybe you can only have references to GAME variables? idk man

For functions it's easy, just use a Variable parameter, granted performance a valid optimization is probably making every parameter a Variable one to avoid unnecessary cloning on immutables aswell.

#pagebreak()

= Player
```rust
type Player = String;

impl Player {
  private fn select(val self) {
    emit `select_object "PlayerByName" args($self$)`;
  }

  private fn deselect(val self) {
    emit `select_object "Reset"`;
  }

  fn sendMessage(var self, val message: Text) {
    // tags(..) is default, tags("TagA"="Value", ..) means everything default apart from TagA
    self.select();
    emit `player_action "SendMessage" args($message$) tags(..)`;
    self.deselect();
  }

  fn health(val self) -> Num {
    self.select();
    val health = gval("Health", .Selection);
    self.deselect();
    return health;
  }
}
```

#pagebreak()

= Selections
```rust
object Selections;

impl Selections {
  private fn emitPlayers() -> List<Player> {
    val uuids = GameValues.selectedUuids();
    return uuids.map(uuid -> uuid as Player);
    // or maybe
    return uuids.map(it as Player);
  }
}

object GameValues;

impl GameValues {
  pub fn selectedUuids() -> List<String> {
    return gval("Selected Player UUIDs") as List<String>
  }
}
```

#pagebreak()

= Conditionals
== Basic
```rust
@Event
fn Join(var event: PlayerJoinEvent) {

}
```

#pagebreak()

= Enums
```rust
enum SelectionType {
  Default, Selected, Victim, Attacker //...
}

fn doSomething(val thing: SelectionType) {
  when(thing) {
    .Default -> // ...
    .Selected {
      // ...
    }
    else -> {
      // Only required when non-exhaustive without it
    }
  }
}

fn callingDoSomething() {
  doSomething(SelectionType.Attacker);
  doSomething(.Attacker); // same thing
}
```

#pagebreak()

= When
```rust
// Simple equality when clause
when (1 + 2) {
  1 -> debug("Omg 1");
  2 -> debug("Wtf 2");
  3 -> debug("wahoo");
  else x -> {
    debug("Not 1, 2 or 3 instead: " + x);
  }
}

// Neater than else if spam!
val x: Num = // ...
when {
  x % 2 == 0 -> {
    debug("Even")
  }
  x % 2 == 1 && x != 1 -> {
    debug("Odd that isn't 1")
  }
  else -> {
    debug("This could be 1 or any decimal number");
  }
}

// Special case for enums
when (enumGuy) {
  .Thing1 -> {

  }
  .Thing2 -> {

  }
  .Thing3 -> {

  }
}
// Fine without an else, if it's exhaustive
```

#pagebreak()

= Tuples
```rust
fn getHealthAndMaxHealth(val player: Player) -> (Num, Num) {
  return (player.health(), player.maxHealth());
}

fn example() {
  val player: Player = //...

  val (health, maxHealth) = getHealthAndMaxHealth(player);
  // or
  val health, var maxHealth = getHealthAndMaxHealth(player);

  typeof((1, 2)) == "Tuple(Num, Num)"
  // df representation depends, for now might just be literally that string in the $type field
}
```

#pagebreak()

= Function Literals
```rust
fn mapNumToNum(val list: List<Num>, val map: fn(Num) -> Num) -> List<Num> {
  var newList = List<Num>.new();
  for (element in list) {
    newList.append(map(element));
  }
  return newList;
}
```

= Generics
```rust
fn <_From, _To> map(val list: List<_From>, val map: fn(_From) -> _To) -> List<_To> {
  var newList = List<_To>.new();
  for (element in list) {
    newList.append(map(element));
  }
  return newList;
}

val from = listOf(
  (1, "Hello"),
  (2, "Bye")
);

val mapper: fn(Num, String) -> String = fn(num: Num, string: String) -> String {
  return string.repeat(num);
}

val to = map(from, mapper);
// ["Hello", "ByeBye"]
```

#pagebreak()

= Saved Variables
```rust
struct PlayerData {
  val uuid: String,
  var money: Num,
  var level: Num
}

fn savePlayerData(val data: PlayerData) {
  val baseString = "S_" + data.uuid + "_";

  val vars = SavedVariables; // SavedVariables and the future Bucket abstraction will implement the same interface providing save(String, Any) and load(String) -> Any

  vars.save(baseString + "money", data.money);
  vars.save(baseString + "level", data.level);
}
```

#pagebreak()

= Pointers
```rust
Have to store variable name?
```

#pagebreak()

#pagebreak()

= Random Ramblings
```rust
val x = 5;
```
There's going to be a variable called "x" on the df side.\
This variable will look something like (it's a List):
```
["Num", 5]
```
because
```rust
struct Balls {
  val x: Num,
  val y: Num
}

val x = Balls {
  x: 5,
  y: 6
}
```
will be
```
["Balls", 5, 6]
```
this type information is needed because:
```rust
interface DoesSomething {
  fn do(something: Num);
}

struct A {
  val num: Num
}

impl DoesSomething for A {
  fn do(something: Num) {
    debug("A");
  }
}

struct B {
  val string: String
}

impl DoesSomething for B {
  fn do(something: Num) {
    debug("B");
  }
}

fn doesSomethingWithSomething(does: DoesSomething) {
  does.do(5);
}

doesSomethingWithSomething(A {num: 5});
doesSomethingWithSomething(B {string: "Hello"});
```
To be able to call the proper function at runtime.

Another reason is
```
val x = // ...

fn typeName(val b: Any) -> String {
  return typeof(b); // always return "Any"
}

debug(typeName(5)); // this SHOULD print "Num"

enum DamageCause {
  FallDamage, Unknown
}

fn damagePlayer(cause: DamageCause = .Unknown) {

}

fn silly(test: fn() -> String) {
  debug(test());
}

fn another() {
  val randomint = rand().blablabalblaa();
  silly(() -> randomint); // I think I'll require all variables used in a function literal to be constant (known at compile time)

  debug(randomint);
}

dffunction "$literalFunction_1" out var:
  var = randomint;
  returns;
```

#pagebreak()

```rust

fn loadConstants() {}

fn spawnEntities() {}

fn gameLoop() {}

object Global {
  var startupTimestamp: Num = -1,
}

@Event
fn startup(var event: PlotStartupEvent) {
  var vars = SavedVars;
  val sessions = vars.load("sessions") as Num;
  vars.save("session", sessions + 1);

  Global.startupTimestamp = Values.currentTimestamp();

  loadConstants();
  startProcess(spawnEntities, .NoTargets);
  startProcess(gameLoop, .NoTargets);
}

```