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
@PlayerEventProvider("TakeDamage")
object PlayerTakeDamageEvent;

impl PlayerTakeDamageEvent {
  fn cancel(var this) {
    emit `game_action "CancelEvent"`;
  }

  fn uncancel(var this) {
    emit `game_action "UncancelEvent"`;
  }

  fn victim(this) -> Player {
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

fn increment(var value: Num) {
  value = value + 1;
}

fn addOne(value: Num) -> Num {
  return value + 1;
}

fn test(arg: Num, var arg2: Num) -> Num {
  arg2 = arg + 1;
  return arg2;
}

fn print(value: Num) {
  // Emits as DiamondFire function `print(Num)`
}

fn print(value: String) {
  // Emits as DiamondFire function `print(String)`
}

fn example() {
  var mutable = 1;
  val immutable = 2;

  increment(var mutable); // Required when passing to a var parameter
  increment(mutable); // Error: mutable parameter requires var
  increment(var immutable); // Error: vals cannot be passed as mutable references
  increment(var someStruct.member); // Error: mutable references must be plain identifiers in V1

  val result = test(1, var mutable); // Returning calls are expressions
  print(1); // Calls `print(Num)`
  print("hello"); // Calls `print(String)`
}
```

There will need to be consideration on how struct members are handled, will they always be cloned on intialization? Otherwise local variables could escape their context and that'd break things.

Function parameters are immutable by default: `value: Num`. Mutable parameters
are written as `var value: Num` and must be called with `var identifier`.
The older `val value: Num` parameter syntax is not valid.

Functions with `-> Type` get an implicit DiamondFire `$out` variable parameter.
Returning from those functions assigns the returned value to `$out` and emits a
DiamondFire `control "Return"` block. A typed function must contain at least one
`return expr;`.

Function DiamondFire identifiers include parameter types to support overloading.
For example, `fn print(value: Num)` emits as `print(Num)`, while
`fn print(value: String)` emits as `print(String)`. Calls are written with the
source name, and the compiler resolves the overload from argument types.
Functions inside `impl Type { ... }` emit with qualified identifiers. A function
without `this` is static and is called as `Type.name(...)`. A function whose
first parameter is `this` is a member function and is called as
`value.name(...)`; `var this` requires the receiver to be stored in a `var`.
The receiver parameter is untyped because the impl target supplies the type.

For now mutable arguments only work with plain local identifiers or reference
dereferences like `var *ptr`. Supporting
`var someStruct.member` needs a separate design for member storage and escaping and
is intentionally a future problem.

Maybe you can only have references to GAME variables? idk man

For functions it's easy, just use a Variable parameter, granted performance a valid optimization is probably making every parameter a Variable one to avoid unnecessary cloning on immutables aswell.

#pagebreak()

= Types and Literals
```rust
val balls: String = "hi";
val styled: Text = s"<green>Hello World";
val enabled: Boolean = true;
val disabled = false; // inferred as Boolean
val sum: Num = 1 + 2 * 3;
val both: Boolean = enabled && disabled;
val either: Boolean = enabled || disabled;
```

For now supported assignment expressions are numbers, strings, styled text components,
booleans, identifiers, parentheses, numeric `+ - * / %`, and boolean `&& ||`.
Booleans are represented as numbers on the DiamondFire side: `true` is `1` and
`false` is `0`.

#pagebreak()

= Structs
```rust
struct PlayerData {
  uuid: String,
  money: Num,
  displayName: Text,
}

fn example() {
  val immutableData = PlayerData {
    uuid: "player-uuid",
    money: 5,
    displayName: s"<green>Finn",
  };

  var mutableData = PlayerData {
    uuid: "player-uuid",
    money: 5,
    displayName: s"<green>Finn",
  };

  val money = mutableData.money;
  mutableData.money = money + 1; // OK, because mutableData is var
  immutableData.money = 10; // Error, because immutableData is val

  val kind = typeof(mutableData); // "PlayerData"
  val primitiveKind = typeof(5); // "Num"
}
```

Struct members do not currently carry their own `val` or `var` marker. Member
assignment is controlled by the variable that stores the struct: a `val`
struct disallows all member writes, while a `var` struct allows them.

The default representation is a DiamondFire list:
```
["PlayerData", "player-uuid", 5, "<green>Finn"]
```
DiamondFire list indexes are 1-based, so the type name is at index `1`, and
the first user field is at index `2`.
Reads from list-backed primitive fields lower to compact value placeholders.
For example, a `Num` field read like `mutableData.money` lowers as a `num`
item with value `%index(mutableData,3)`. `String` fields use `txt` items and
`Text` fields use `comp` items.

The compiler can also use dictionary-backed structs with `--dictstructs` or
`-ds`:
```
{"$type": "PlayerData", "uuid": "player-uuid", "money": 5, "displayName": "<green>Finn"}
```
The `$type` key is reserved for type metadata.
Reads from dictionary-backed primitive fields use the same value-item style,
for example a `txt` item with value `%entry(mutableData,uuid)`.

Primitive values are not boxed as `[type, value]` in this pass. DiamondFire's
native `num`, `txt`, `comp`, and Boolean-as-`num` values are shorter and avoid
extra conversion blocks for arithmetic and text operations. For now,
`typeof(...)` on primitive expressions uses the compiler-known static type
(`Num`, `String`, `Text`, or `Boolean`). Runtime primitive inspection can be
added later for erased or `Any`-typed values using DiamondFire variable type
checks where needed.

#pagebreak()

= Player
```rust
type Player = String;

impl Player {
  private fn select(this) {
    emit `select_object "PlayerByName" args($this$)`;
  }

  private fn deselect(this) {
    emit `select_object "Reset"`;
  }

  fn sendMessage(var this, message: Text) {
    // tags(..) is default, tags("TagA"="Value", ..) means everything default apart from TagA
    this.select();
    emit `player_action "SendMessage" args($message$) tags(..)`;
    this.deselect();
  }

  fn health(this) -> Num {
    this.select();
    val health = gval("Health", .Selection);
    this.deselect();
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
  Default, Selection, Victim, Attacker //...
}

fn doSomething(thing: SelectionType) {
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

fn readingEnum(thing: SelectionType) {
  val ordinal: Num = thing.ordinal();
  val name: String = thing.name();
}
```

Enum values are represented like structs using the configured backing mode. List mode stores
`[EnumName, ordinal, name]`; dict mode stores `$type`, `$ordinal`, and `$name`.
The `.Entry` shorthand is only valid when the compiler already knows the expected enum type.

`gval(name: String, type: SelectionType)` emits a DiamondFire game value. The game value name is
matched exactly; `"Name"` and `"Name "` are distinct legacy DiamondFire values.

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
fn getHealthAndMaxHealth(player: Player) -> (Num, Num) {
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
fn mapNumToNum(list: List<Num>, map: fn(Num) -> Num) -> List<Num> {
  var newList = List<Num>.new();
  for (element in list) {
    newList.append(map(element));
  }
  return newList;
}
```

= Generics
```rust
inline fn <T> id(value: T) -> T {
  return value;
}

val num = id(1); // Num
val text = id("hello"); // String
```

Generic function type parameters are compile-time only. `Any` is the top type:
any value can be passed where `Any` is expected. `List<T>` is assignable to
`List<Any>`, and `Dict<T>` is assignable to `Dict<Any>`, but the reverse is not
accepted in this pass.

= Lists and Dictionaries
```rust
import std.prelude;

fn example() {
  val empty: List<Num> = List.of();
  var nums = List.of(1, 2, 3);
  nums.append(4);
  val first: Num = nums.get(1);
  nums.set(2, first);
  val length: Num = nums.length();

  var dict = Dict<Num>.new();
  dict.set("money", 5);
  val money: Num = dict.get("money");
  val keys: List<String> = dict.keys();
  val values: List<Num> = dict.values();
  val size: Num = dict.size();
}
```

`List<T>` and `Dict<T>` are compiler-known erased collection types. Their
runtime representation is DiamondFire's native list/dictionary value; element
types are enforced by the compiler only. `Dict<T>` uses `String` keys in this
pass.

The stdlib collection methods are regular Flang functions implemented with
`emit`, not compiler-intrinsic collection operations. `List.of` is currently
provided as fixed overloads from 0 through 10 arguments; true varargs are a
future language feature.

#pagebreak()

= Saved Variables
```rust
struct PlayerData {
  uuid: String,
  money: Num,
  level: Num
}

fn savePlayerData(data: PlayerData) {
  val baseString = "S_" + data.uuid + "_";

  val vars = SavedVariables; // SavedVariables and the future Bucket abstraction will implement the same interface providing save(String, Any) and load(String) -> Any

  vars.save(baseString + "money", data.money);
  vars.save(baseString + "level", data.level);
}
```

#pagebreak()

= Pointers
```rust
struct Point {
  x: Num
}

fn example() {
  var ptr: &Point = malloc(Point, Point { x: 1 });
  ptr.x = 2; // Automatically dereferences and writes $PTR_n.x
  *ptr = Point { x: 3 }; // Replaces the whole pointed-to game variable
  takesMutablePoint(var *ptr); // Passes the pointed-to game variable
  free(ptr); // Purges the pointed-to game variable
}
```

Reference values use the compiler-known type `&Type` and are always represented
as a list:
```
["Type", "$PTR_0"]
```
Index `1` is the referent type name and index `2` is the DiamondFire game
variable name. `malloc(Type)` allocates a new pointer name using the game
variable `$FLANG_PTR_COUNTER`; `malloc(Type, value)` also initializes the
pointed-to game variable. `free(ref)` purges the pointed-to game variable with
the set-variable purge action. The local reference may be `val`; that only
prevents changing which pointer the reference stores, not mutating the pointed-to
game variable.

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
  x: Num,
  y: Num
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
  num: Num
}

impl A {
  fn do(something: Num) {
    debug("A");
  }
}

struct B {
  string: String
}

impl B {
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

fn typeName(b: Any) -> String {
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
