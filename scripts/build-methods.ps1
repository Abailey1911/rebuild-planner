param([string]$GearRes, [string]$Out)
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
# A switch is a handful of pieces, not a second full setup.
$switchSlots = 'WEAPON', 'SHIELD', 'HEAD', 'BODY', 'LEGS', 'AMULET', 'CAPE', 'AMMO'

$goals = @(
	[ordered]@{
		name = 'Twisted bow'; id = 20997; buyId = 20997; style = 'RANGED'
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

$doc = [ordered]@{
	note = 'gpPerHour figures are from the OSRS Wiki money making guide (CC BY-NC-SA 3.0), snapshot 2026-11-11. Gear lists follow the wiki strategy pages.'
	goals = $goals
}
[IO.File]::WriteAllText($Out, ($doc | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding($false)))
"wrote $Out ($((Get-Item $Out).Length) bytes)"
