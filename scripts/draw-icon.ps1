# Draws icon.png: a stack of gold coins with a green "rebuild" arrow curling around it.
# Drawn at 8x and scaled down, so edges stay smooth at 48px and at the 16px sidebar size.
param([string]$Out = (Join-Path $PSScriptRoot '..\icon.png'))
Add-Type -AssemblyName System.Drawing

$S = 8
$W = 48 * $S
$big = New-Object System.Drawing.Bitmap $W, $W
$g = [System.Drawing.Graphics]::FromImage($big)
$g.SmoothingMode = 'AntiAlias'
$g.Clear([System.Drawing.Color]::Transparent)

function C($a, $r, $gg, $b) { [System.Drawing.Color]::FromArgb($a, $r, $gg, $b) }
function P($v) { [single]($v * $S) }

$rim = C 255 92 60 8
$edgeDark = C 255 150 104 14
$edgeLight = C 255 214 160 34

# One coin seen from slightly above: a thick edge band, then the face on top.
function Coin([double]$cx, [double]$cy, [double]$rx, [double]$ry, [double]$depth) {
	$x = $cx - $rx; $y = $cy - $ry
	# Edge band
	$band = New-Object System.Drawing.Drawing2D.GraphicsPath
	$band.AddArc((P $x), (P ($y + $depth)), (P (2 * $rx)), (P (2 * $ry)), 0, 180)
	$band.AddLine((P $x), (P ($cy + $depth)), (P $x), (P $cy))
	$band.AddArc((P $x), (P $y), (P (2 * $rx)), (P (2 * $ry)), 180, -180)
	$band.CloseFigure()
	$edge = New-Object System.Drawing.Drawing2D.LinearGradientBrush ([System.Drawing.PointF]::new((P $x), 0)), ([System.Drawing.PointF]::new((P ($cx + $rx)), 0)), $edgeLight, $edgeDark
	$g.FillPath($edge, $band)
	# Milled ridges on the edge
	$ridge = New-Object System.Drawing.Pen ((C 120 90 60 8)), (P 0.5)
	for ($k = 1; $k -lt 12; $k++) {
		$t = [Math]::PI * $k / 12
		$px = $cx - $rx * [Math]::Cos($t); $py = $cy + $ry * [Math]::Sin($t)
		$g.DrawLine($ridge, (P $px), (P $py), (P $px), (P ($py + $depth)))
	}
	$g.DrawPath((New-Object System.Drawing.Pen $rim, (P 0.9)), $band)

	# Face
	$face = New-Object System.Drawing.Drawing2D.GraphicsPath
	$face.AddEllipse((P $x), (P $y), (P (2 * $rx)), (P (2 * $ry)))
	$shine = New-Object System.Drawing.Drawing2D.PathGradientBrush $face
	$shine.CenterPoint = [System.Drawing.PointF]::new((P ($cx - $rx * 0.35)), (P ($cy - $ry * 0.45)))
	$shine.CenterColor = C 255 255 246 170
	$shine.SurroundColors = @((C 255 230 172 30))
	$g.FillPath($shine, $face)
	# Inner ring stamped into the face
	$g.DrawEllipse((New-Object System.Drawing.Pen ((C 170 168 116 12)), (P 0.7)),
		(P ($x + $rx * 0.22)), (P ($y + $ry * 0.22)), (P (2 * $rx * 0.78)), (P (2 * $ry * 0.78)))
	$g.DrawPath((New-Object System.Drawing.Pen $rim, (P 0.9)), $face)
}

# Soft shadow under the stack
$shadow = New-Object System.Drawing.SolidBrush (C 70 0 0 0)
$g.FillEllipse($shadow, (P 5), (P 38), (P 32), (P 7))

# Bottom to top, each coin nudged so the stack looks hand-piled
Coin 20 36 14 5 4
Coin 22 30 14 5 4
Coin 19 24 14 5 4
Coin 21.5 18 14 5 4

# Rebuild arrow: a thick green arc rising up the right side, ending in an arrowhead
$arrowPath = New-Object System.Drawing.Drawing2D.GraphicsPath
$arrowPath.AddArc((P 12), (P 7), (P 32), (P 32), 75, -150)
$outline = New-Object System.Drawing.Pen ((C 255 18 70 22)), (P 6.2)
$outline.StartCap = 'Round'
$g.DrawPath($outline, $arrowPath)
$body = New-Object System.Drawing.Pen ((C 255 92 214 92)), (P 4)
$body.StartCap = 'Round'
$g.DrawPath($body, $arrowPath)
$hl = New-Object System.Drawing.Pen ((C 160 200 255 200)), (P 1.2)
$g.DrawPath($hl, $arrowPath)

# Arrowhead at the arc's end, pointing back over the top of the stack
$end = $arrowPath.PathPoints[$arrowPath.PointCount - 1]
$tip = [System.Drawing.PointF]::new($end.X - (P 7), $end.Y - (P 1.5))
$head = @(
	$tip,
	[System.Drawing.PointF]::new($end.X + (P 1.5), $end.Y - (P 5.5)),
	[System.Drawing.PointF]::new($end.X + (P 1.5), $end.Y + (P 4.5))
)
$g.FillPolygon((New-Object System.Drawing.SolidBrush (C 255 92 214 92)), $head)
$g.DrawPolygon((New-Object System.Drawing.Pen ((C 255 18 70 22)), (P 1.1)), $head)

$g.Dispose()

$small = New-Object System.Drawing.Bitmap 48, 48
$s = [System.Drawing.Graphics]::FromImage($small)
$s.InterpolationMode = 'HighQualityBicubic'
$s.PixelOffsetMode = 'HighQuality'
$s.SmoothingMode = 'AntiAlias'
$s.DrawImage($big, 0, 0, 48, 48)
$s.Dispose()
$small.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)

# A 4x preview for checking by eye
$preview = New-Object System.Drawing.Bitmap 192, 192
$p = [System.Drawing.Graphics]::FromImage($preview)
$p.Clear((C 255 40 40 40))
$p.InterpolationMode = 'NearestNeighbor'
$p.DrawImage($small, 0, 0, 192, 192)
$p.Dispose()
$preview.Save(($Out -replace '\.png$', '-preview.png'), [System.Drawing.Imaging.ImageFormat]::Png)
"wrote $Out"
