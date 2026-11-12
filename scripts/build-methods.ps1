# -Equipment is the wiki DPS calculator's equipment list:
# https://raw.githubusercontent.com/weirdgloop/osrs-dps-calc/main/cdn/json/equipment.json
param([Parameter(Mandatory)][string]$GearRes, [Parameter(Mandatory)][string]$Out, [Parameter(Mandatory)][string]$Equipment)
$ErrorActionPreference = 'Stop'

$tables = @()
foreach ($f in 'bis.json', 'bis-raids.json', 'bis-bosses.json') { $part = Get-Content (Join-Path $GearRes $f) -Raw -Encoding UTF8 | ConvertFrom-Json; $tables += $part }
$monsters = Get-Content (Join-Path $GearRes 'monsters.json') -Raw -Encoding UTF8 | ConvertFrom-Json

function Table($owner, $style) {
	$t = $tables | Where-Object { ($_.monster -eq $owner -or $_.group -eq $owner) -and $_.style -eq $style -and -not $_.variant } | Select-Object -First 1
	if (-not $t) { throw "No table for $owner / $style" }
	$t
}
function Role($owner, $style, $main, [string[]]$onlySlots, [string]$targetName) {
	$t = Table $owner $style
	$slots = foreach ($s in $t.slots) {
		if ($onlySlots -and ($onlySlots -notcontains $s.slot)) { continue }
		[ordered]@{ slot = $s.slot; items = @($s.items); ids = @($s.ids) }
	}
	$r = [ordered]@{ style = $style; main = $main; slots = @($slots) }
	# A switch is judged against the boss it is actually used on in that raid.
	if ($targetName) { $r.target = Target $targetName }
	$r
}
function Target($name) {
	$m = $monsters | Where-Object name -eq $name | Select-Object -First 1
	if (-not $m) { throw "No monster $name" }
	[ordered]@{ name = $m.name; defenceLevel = $m.defenceLevel; magicLevel = $m.magicLevel; dstab = $m.dstab; dslash = $m.dslash; dcrush = $m.dcrush; dmagic = $m.dmagic; drange = $m.drange }
}
# Item name -> id, from the wiki DPS calculator's equipment list.
$equip = Get-Content $Equipment -Raw | ConvertFrom-Json
$idByName = @{}
foreach ($e in $equip) { $k = $e.name.ToLower(); if (-not $idByName.ContainsKey($k) -or $e.version -eq 'Charged') { $idByName[$k] = [int]$e.id } }

# Bosses with no Gear Upgrade entry, from their wiki infoboxes. Ranged defence
# is the 'standard' value, which arrows and bolts are rolled against.
function Stats($name, $def, $mage, $dstab, $dslash, $dcrush, $dmagic, $drange) {
	[ordered]@{ name = $name; defenceLevel = $def; magicLevel = $mage; dstab = $dstab; dslash = $dslash; dcrush = $dcrush; dmagic = $dmagic; drange = $drange }
}
$ezekiel = Stats 'Ezekiel' 200 200 150 90 35 200 370
$moia = Stats 'Moia' 195 350 20 110 15 30 120

# A setup straight from a wiki strategy page's Recommended equipment block.
$wikiSlot = @{ head = 'HEAD'; neck = 'AMULET'; cape = 'CAPE'; body = 'BODY'; legs = 'LEGS'; weapon = 'WEAPON'; shield = 'SHIELD'; ammo = 'AMMO'; hands = 'GLOVES'; feet = 'BOOTS'; ring = 'RING' }
function PageRole($page, $label, $style, $main, $target) {
	$m = [regex]::Matches($page, '(?s)\{\{Recommended equipment(.*?)\n\}\}') | Where-Object { $_.Groups[1].Value -match ('(?m)^\|\s*style\s*=\s*' + [regex]::Escape($label) + '\s*$') } | Select-Object -First 1
	if (-not $m) { throw "No block '$label'" }
	$slots = [ordered]@{}
	foreach ($row in [regex]::Matches($m.Groups[1].Value, '(?m)^\|\s*([a-z]+)(\d+)\s*=\s*(.*)$')) {
		$key = $wikiSlot[$row.Groups[1].Value]; if (-not $key) { continue }
		if (-not $slots.Contains($key)) { $slots[$key] = [ordered]@{ slot = $key; items = @(); ids = @() } }
		foreach ($p in [regex]::Matches($row.Groups[3].Value, '\{\{plink\|([^|}]+)')) {
			$n = $p.Groups[1].Value.Trim(); $id = $idByName[$n.ToLower()]
			if (-not $id -or ($slots[$key].items -contains $n)) { continue }
			$slots[$key].items += $n; $slots[$key].ids += $id
		}
	}
	$r = [ordered]@{ style = $style; main = $main; slots = @($slots.Values | Where-Object { $_.items.Count -gt 0 }) }
	if ($target) { $r.target = $target }
	$r
}
$tfa = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'tfa-strategies.wikitext'))

# What a full charge of each weapon costs, so the runes are never sold off.
function Charge($id, $qty, $name) { [ordered]@{ id = $id; qty = $qty; name = $name } }

# A switch is a handful of pieces, not a second full setup.
$switchSlots = 'WEAPON', 'SHIELD', 'HEAD', 'BODY', 'LEGS', 'AMULET', 'CAPE', 'AMMO'

$goals = @(
	[ordered]@{
		name = 'Twisted bow'; id = 20997; buyId = 20997; style = 'RANGED'; charges = @()
		methods = @(
			[ordered]@{
				name = 'Doom of Mokhaiotl (delves 1-8)'; gpPerHour = 9518000
				note = 'Wiki rate for delves 1-8 with a Twisted bow. Deeper delves pay more but need more than a rebuild kit.'
				target = Target 'Doom of Mokhaiotl (delve 1)'; gearMultiplier = 1
				roles = @((Role 'Doom of Mokhaiotl (delve 1)' 'RANGED' $true $null))
			},
			[ordered]@{
				name = 'Phantom Muspah'; gpPerHour = 5838000
				note = 'Wiki rate for the Twisted bow method. Lower gear and skill bar than the Doom.'
				target = Target 'Phantom Muspah'; gearMultiplier = 1
				roles = @((Role 'Phantom Muspah' 'RANGED' $true $null))
			}
		)
	},
	[ordered]@{
		name = "Tumeken's shadow"; id = 27275; buyId = 27277; style = 'MAGIC'
		charges = @((Charge 566 40000 'Soul rune'), (Charge 562 100000 'Chaos rune'))
		methods = @(
			[ordered]@{
				name = 'Tombs of Amascut (solo 450)'; gpPerHour = 5529000
				note = 'Wiki rate for solo 450 raid level. The shadow does most of the raid, but you still need ranged and melee switches to finish it in good time.'
				target = Target 'Akkha'; gearMultiplier = 4
				roles = @((Role 'Tombs of Amascut' 'MAGIC' $true $null), (Role 'Tombs of Amascut' 'RANGED' $false $switchSlots 'Zebak'), (Role 'Tombs of Amascut' 'MELEE' $false $switchSlots 'Wardens (P3, enraged)'))
			},
			[ordered]@{
				name = 'Tombs of Amascut (expert, 300+)'; gpPerHour = 3223000
				note = 'Wiki rate for expert raids. Use this if 450s are out of reach on the rebuild kit.'
				target = Target 'Akkha'; gearMultiplier = 4
				roles = @((Role 'Tombs of Amascut' 'MAGIC' $true $null), (Role 'Tombs of Amascut' 'RANGED' $false $switchSlots 'Zebak'), (Role 'Tombs of Amascut' 'MELEE' $false $switchSlots 'Wardens (P3, enraged)'))
			},
			[ordered]@{
				name = 'The Whisperer'; gpPerHour = 4903000
				note = 'Wiki rate. Magic only, so no switches to keep.'
				target = Target 'The Whisperer'; gearMultiplier = 3
				roles = @((Role 'The Whisperer' 'MAGIC' $true $null))
			}
		)
	},
	[ordered]@{
		name = 'Scythe of vitur'; id = 22325; buyId = 22486; style = 'MELEE'
		charges = @((Charge 565 40000 'Blood rune'), (Charge 22446 200 'Vial of blood'))
		methods = @(
			[ordered]@{
				name = 'Theatre of Blood'; gpPerHour = 8044000
				note = 'Wiki rate for a competent team. Needs ranged and magic switches as well as the melee setup.'
				target = Target 'Verzik Vitur (P3)'; gearMultiplier = 1
				roles = @((Role 'Theatre of Blood' 'MELEE' $true $null), (Role 'Theatre of Blood' 'RANGED' $false $switchSlots 'Verzik Vitur (P1)'), (Role 'Theatre of Blood' 'MAGIC' $false $switchSlots 'Verzik Vitur (P2)'))
			},
			[ordered]@{
				name = 'Duke Sucellus'; gpPerHour = 6751000
				note = 'Wiki rate. Melee only, so no switches to keep.'
				target = Target 'Duke Sucellus'; gearMultiplier = 1
				roles = @((Role 'Duke Sucellus' 'MELEE' $true $null))
			},
			[ordered]@{
				name = 'Vardorvis'; gpPerHour = 6496000
				note = 'Wiki rate. Melee only, so no switches to keep.'
				target = Target 'Vardorvis'; gearMultiplier = 1
				roles = @((Role 'Vardorvis' 'MELEE' $true $null))
			}
		)
	}
)

$goals += [ordered]@{
	name = 'Obligator'; id = 34715; buyId = 34717; style = 'MELEE'
	charges = @((Charge 34669 667 'Ascension shards'), (Charge 561 40000 'Nature rune'), (Charge 560 40000 'Death rune'))
	methods = @(
		[ordered]@{
			name = 'The Fractured Archive (duo)'; gpPerHour = 23686000
			note = 'Wiki rate for duos. The Obligator does the melee bosses; Masori and Ancestral switches handle the rest, after the wiki strategy page.'
			target = $ezekiel; gearMultiplier = 3
			roles = @((PageRole $tfa 'Melee switch' 'MELEE' $true $null), (PageRole $tfa 'Ranged switch' 'RANGED' $false $moia), (PageRole $tfa 'Magic switch' 'MAGIC' $false $moia))
		},
		[ordered]@{
			name = 'The Fractured Archive (trio)'; gpPerHour = 20274000
			note = 'Wiki rate for trios.'
			target = $ezekiel; gearMultiplier = 3
			roles = @((PageRole $tfa 'Melee switch' 'MELEE' $true $null), (PageRole $tfa 'Ranged switch' 'RANGED' $false $moia), (PageRole $tfa 'Magic switch' 'MAGIC' $false $moia))
		}
	)
}

$doc = [ordered]@{
	note = 'gpPerHour figures are from the OSRS Wiki money making guide (CC BY-NC-SA 3.0), snapshot 2026-11-11. Gear lists follow the wiki strategy pages.'
	goals = $goals
}
[IO.File]::WriteAllText($Out, ($doc | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding($false)))
"wrote $Out ($((Get-Item $Out).Length) bytes)"
