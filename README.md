# Rebuild Planner

A RuneLite plugin for rebuilding into a megarare. Pick the item you want, and it reads
your bank, inventory and worn gear to tell you:

- **what to sell** to afford it, after the 2% Grand Exchange tax;
- **what to keep**, so you can still earn the money back;
- **what to buy**, if the money-making method needs gear you do not have;
- **where to earn it back**, with the wiki's gp/hr and an estimate for your own kit.

## How it chooses what to sell

1. **Spare bank items first.** Anything worth 1m+ that is not part of the kit. Supplies
   (potions, food, runes, ammo) keep a working reserve, so 1,000 prayer potions becomes
   "sell 666, keep 334".
2. **Expensive switches next.** A raid switch only has to exist, so a 160m Masori body
   used as a ranged switch is swapped for something cheaper before anything else.
3. **The main setup last.** Pieces are sold in order of most gp freed per percent of damage
   lost, and only up to a damage cap (15% by default). Selling Ancestral to afford a
   Tumeken's shadow loses more than it gains, so the plugin will not suggest it.

## Megarares and methods

| Megarare | Earn it back at |
|---|---|
| Twisted bow | Doom of Mokhaiotl (delves 1-8), Phantom Muspah |
| Tumeken's shadow | Tombs of Amascut (450 or expert), The Whisperer |
| Scythe of vitur | Theatre of Blood, Duke Sucellus, Vardorvis |

## Settings

- **Smallest stack to sell** - bank stacks worth less than this are ignored (default 1m).
- **Supplies to keep** - gp of each supply stack held back (default 3m).
- **Most damage to give up** - cap on damage lost from the main setup (default 15%).

## Data

Money-making rates and gear lists come from the
[Old School RuneScape Wiki](https://oldschool.runescape.wiki/) under
[CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/). Prices are live
from RuneLite's own price feed; nothing is sent to any third-party server.
