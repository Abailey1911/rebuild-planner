# Gear Upgrade: Rebuild Planner

A companion to [Gear Upgrade](https://github.com/Abailey1911/gear-upgrade), listed directly beneath it in the sidebar.

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

Enough runes for one full charge of the megarare are always held back, and if the
rebuilt setup would barely beat the weapon you already use, the plan says it is not
worth it.

## Making it your own

Open your bank once so the plugin can read it, then use the **Sell** tab:

- **✕** next to anything you would rather keep; the plan makes it up elsewhere or tells
  you it cannot.
- **Change an amount** on any stack, e.g. sell 5k soul runes rather than 10k.
- **Pick the replacement** for a kit piece being sold - click the ▾ beside it for every
  option in that slot, best first (Bandos tassets in place of Oathplate legs, say).
- **Sell something else** - add an item the plan was keeping, such as your current weapon.
- **Save** a plan under a name and reopen or update it later.

A "What changed" card shows what each of your changes added to or dropped from the plan.

## Megarares and methods

| Megarare | Earn it back at |
|---|---|
| Twisted bow | Doom of Mokhaiotl (delves 1-8), Phantom Muspah |
| Tumeken's shadow | Tombs of Amascut (450 or expert), The Whisperer |
| Scythe of vitur | Theatre of Blood, Duke Sucellus, Vardorvis |
| Obligator | The Fractured Archive |

## Smaller upgrades

- **Avernic treads** - no money-making method. The plan finds your best gear in each
  style, checks how much damage the treads add to it, keeps that gear, and sells around
  it. The boots the treads replace are up for sale unless you exclude them to upgrade the
  treads with later.
- **Something else…** - search for any item (best for upgrades around 10m to 100m). The
  plan sells junk only: supplies above your reserve, drops and anything else that is not
  gear from the planner's lists. Your setups are never touched.

## Settings

- **Smallest stack to sell** - bank stacks worth less than this are ignored (default 1m).
- **Supplies to keep** - gp of each supply stack held back (default 3m).
- **Most damage to give up** - cap on damage lost from the main setup (default 15%).
- **Most switch damage to give up** - how far raid switches may be sold down (default 40%).
- **Least damage gain to rebuild** - below this gain over your current weapon, the plan
  says the rebuild is not worth it (default 10%).

## Data

Money-making rates and gear lists come from the
[Old School RuneScape Wiki](https://oldschool.runescape.wiki/) under
[CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/). Prices are live
from RuneLite's own price feed; nothing is sent to any third-party server.
