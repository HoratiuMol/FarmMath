package com.farmmathbuilder.app.domain

import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A single precomputed, screen-ready triangle of the barn mesh: normalized
 * (tile-footprint-relative) X/Y offsets for its 3 vertices, plus its
 * flat-shaded color. Depth sorting has already been applied when the list
 * is built (see [BarnMesh.load]), so triangles are stored in the correct
 * back-to-front paint order and can be drawn top-to-bottom as-is.
 */
data class BarnTriangle(
    val normX0: Float,
    val normY0: Float,
    val normX1: Float,
    val normY1: Float,
    val normX2: Float,
    val normY2: Float,
    val color: Color
)

/**
 * Builds the "Grand Timber Frame Barn" procedurally — a rectangular body,
 * a flared gambrel roof, an exposed king-post truss, cross-braced doors,
 * and flanking lean-to porches — then rotates/shades/depth-sorts it with a
 * fixed camera and caches the result as a flat list of screen-ready
 * [BarnTriangle]s, same as the previous Wings3D-parsed model did. All math
 * (rotateY, rotateX, faceNormal, normalize, dot, shade, the auto-orienting
 * addTriAuto/addQuadAuto trick, the box/gabledRoof/beam2D/xBraceDoor/
 * leanToSlab builders) is ported 1:1 from the validated reference
 * implementation at docs/previews/barn-timberframe-preview.html — this is
 * the source of truth, not reinvented here.
 *
 * The whole computation runs exactly once (cached in [triangles], a `by
 * lazy` singleton) — never per-frame or per-recomposition. Only the cheap
 * final step (mapping normX/normY to screen pixels via tileW*scale) happens
 * at draw time, in FarmGridCanvas.
 */
object BarnMesh {

    private var cached: List<BarnTriangle>? = null

    fun load(): List<BarnTriangle> {
        cached?.let { return it }
        val result = buildFromRaw(buildGrandTimberFrameBarn())
        cached = result
        return result
    }

    private data class Vec3(val x: Float, val y: Float, val z: Float)
    private data class RawTri(val a: Vec3, val b: Vec3, val c: Vec3, val color: Color)

    private fun sub(a: Vec3, b: Vec3) = Vec3(a.x - b.x, a.y - b.y, a.z - b.z)
    private fun cross(u: Vec3, v: Vec3) = Vec3(
        u.y * v.z - u.z * v.y,
        u.z * v.x - u.x * v.z,
        u.x * v.y - u.y * v.x
    )
    private fun dot(a: Vec3, b: Vec3): Float = a.x * b.x + a.y * b.y + a.z * b.z

    private fun normalize(v: Vec3): Vec3 {
        val len = hypot(hypot(v.x.toDouble(), v.y.toDouble()), v.z.toDouble()).toFloat().let { if (it == 0f) 1f else it }
        return Vec3(v.x / len, v.y / len, v.z / len)
    }

    private fun faceNormal(a: Vec3, b: Vec3, c: Vec3): Vec3 = normalize(cross(sub(b, a), sub(c, a)))

    private fun rotateY(p: Vec3, a: Float): Vec3 {
        val cosA = cos(a)
        val sinA = sin(a)
        return Vec3(p.x * cosA + p.z * sinA, p.y, -p.x * sinA + p.z * cosA)
    }

    private fun rotateX(p: Vec3, a: Float): Vec3 {
        val cosA = cos(a)
        val sinA = sin(a)
        return Vec3(p.x, p.y * cosA - p.z * sinA, p.y * sinA + p.z * cosA)
    }

    private fun shade(color: Color, brightness: Float): Color {
        val b = brightness.coerceIn(0.28f, 1.15f)
        val r = (color.red * b).coerceIn(0f, 1f)
        val g = (color.green * b).coerceIn(0f, 1f)
        val bl = (color.blue * b).coerceIn(0f, 1f)
        return Color(r, g, bl, 1f)
    }

    // ---------- procedural mesh builders. Winding is auto-corrected against
    // [ref], a point known to sit inside the solid being built, so hand-picked
    // vertex order never has to be verified face by face. ----------

    private fun addTriAuto(tris: MutableList<RawTri>, a: Vec3, b: Vec3, c: Vec3, color: Color, ref: Vec3) {
        val n = faceNormal(a, b, c)
        val fc = Vec3((a.x + b.x + c.x) / 3f, (a.y + b.y + c.y) / 3f, (a.z + b.z + c.z) / 3f)
        if (dot(n, sub(fc, ref)) < 0f) tris.add(RawTri(a, c, b, color)) else tris.add(RawTri(a, b, c, color))
    }

    private fun addQuadAuto(tris: MutableList<RawTri>, p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, color: Color, ref: Vec3) {
        addTriAuto(tris, p0, p1, p2, color, ref)
        addTriAuto(tris, p0, p2, p3, color, ref)
    }

    private fun box(
        tris: MutableList<RawTri>,
        cx: Float, cy: Float, cz: Float,
        w: Float, h: Float, d: Float,
        color: Color,
        skipTop: Boolean = false
    ) {
        val x0 = cx - w / 2; val x1 = cx + w / 2
        val y0 = cy - h / 2; val y1 = cy + h / 2
        val z0 = cz - d / 2; val z1 = cz + d / 2
        val a = Vec3(x0, y0, z0); val b = Vec3(x1, y0, z0); val c = Vec3(x1, y1, z0); val dd = Vec3(x0, y1, z0)
        val e = Vec3(x0, y0, z1); val f = Vec3(x1, y0, z1); val g = Vec3(x1, y1, z1); val hh = Vec3(x0, y1, z1)
        val ref = Vec3(cx, cy, cz)
        addQuadAuto(tris, e, f, g, hh, color, ref)
        addQuadAuto(tris, b, a, dd, c, color, ref)
        addQuadAuto(tris, a, e, hh, dd, color, ref)
        addQuadAuto(tris, f, b, c, g, color, ref)
        // top face omitted when a roof sits flush on it — coplanar with the
        // roof's eave line, it's always fully hidden underneath, and keeping
        // it around risks a depth-sort tie flashing it through the ridge.
        if (!skipTop) addQuadAuto(tris, dd, hh, g, c, color, ref)
        addQuadAuto(tris, a, b, f, e, color, ref)
    }

    // profilePts: (x,y) polyline, left eave -> ...ridge points... -> right
    // eave, both eaves at the same y (sits flush on a box's wall-top).
    // Produces the sloped roof faces plus fan-triangulated gable ends.
    private fun gabledRoof(
        tris: MutableList<RawTri>,
        cx: Float,
        profilePts: List<Pair<Float, Float>>,
        z0: Float, z1: Float,
        roofColor: Color, gableColor: Color,
        ref: Vec3
    ) {
        val n = profilePts.size
        for (i in 0 until n - 1) {
            val a = Vec3(cx + profilePts[i].first, profilePts[i].second, z0)
            val b = Vec3(cx + profilePts[i + 1].first, profilePts[i + 1].second, z0)
            val c = Vec3(cx + profilePts[i + 1].first, profilePts[i + 1].second, z1)
            val d = Vec3(cx + profilePts[i].first, profilePts[i].second, z1)
            addQuadAuto(tris, a, b, c, d, roofColor, ref)
        }
        for (zz in listOf(z0, z1)) {
            val p0 = Vec3(cx + profilePts[0].first, profilePts[0].second, zz)
            for (i in 1 until n - 1) {
                val pi = Vec3(cx + profilePts[i].first, profilePts[i].second, zz)
                val pi1 = Vec3(cx + profilePts[i + 1].first, profilePts[i + 1].second, zz)
                addTriAuto(tris, p0, pi, pi1, gableColor, ref)
            }
        }
    }

    // a thin coplanar accent panel (door, window...) sitting just proud of a
    // wall/gable face so it never z-fights with it.
    private fun panel(tris: MutableList<RawTri>, cx: Float, cy: Float, cz: Float, w: Float, h: Float, color: Color) {
        addQuadAuto(
            tris,
            Vec3(cx - w / 2, cy - h / 2, cz), Vec3(cx + w / 2, cy - h / 2, cz),
            Vec3(cx + w / 2, cy + h / 2, cz), Vec3(cx - w / 2, cy + h / 2, cz),
            color, Vec3(cx, cy, cz - 0.05f)
        )
    }

    // a thin rectangular strut between two 2D points at a fixed depth — the
    // building block for exposed-truss beams and door X-braces, things a
    // plain axis-aligned box can't make.
    private fun beam2D(
        tris: MutableList<RawTri>,
        x0: Float, y0: Float, x1: Float, y1: Float,
        z: Float, thick: Float, depth: Float, color: Color
    ) {
        val dx = x1 - x0; val dy = y1 - y0
        val l = hypot(dx.toDouble(), dy.toDouble()).toFloat().let { if (it == 0f) 1f else it }
        val nx = -dy / l * thick / 2; val ny = dx / l * thick / 2
        val zb = z - depth / 2; val zf = z + depth / 2
        val p0 = Vec3(x0 - nx, y0 - ny, zb); val p1 = Vec3(x1 - nx, y1 - ny, zb)
        val p2 = Vec3(x1 + nx, y1 + ny, zb); val p3 = Vec3(x0 + nx, y0 + ny, zb)
        val p4 = Vec3(x0 - nx, y0 - ny, zf); val p5 = Vec3(x1 - nx, y1 - ny, zf)
        val p6 = Vec3(x1 + nx, y1 + ny, zf); val p7 = Vec3(x0 + nx, y0 + ny, zf)
        val ref = Vec3((x0 + x1) / 2, (y0 + y1) / 2, z)
        addQuadAuto(tris, p0, p1, p2, p3, color, ref)
        addQuadAuto(tris, p5, p4, p7, p6, color, ref)
        addQuadAuto(tris, p0, p3, p7, p4, color, ref)
        addQuadAuto(tris, p1, p5, p6, p2, color, ref)
        addQuadAuto(tris, p3, p2, p6, p7, color, ref)
        addQuadAuto(tris, p0, p4, p5, p1, color, ref)
    }

    // cross-braced barn door: a flat panel plus an X of beams proud of it —
    // the single most recognizable detail on the reference photos.
    private fun xBraceDoor(
        tris: MutableList<RawTri>,
        cx: Float, yBottom: Float, w: Float, h: Float, z: Float,
        panelColor: Color, braceColor: Color
    ) {
        panel(tris, cx, yBottom + h / 2, z, w, h, panelColor)
        val x0 = cx - w / 2; val x1 = cx + w / 2; val y0 = yBottom; val y1 = yBottom + h
        beam2D(tris, x0, y0, x1, y1, z + 0.02f, 0.055f, 0.03f, braceColor)
        beam2D(tris, x1, y0, x0, y1, z + 0.02f, 0.055f, 0.03f, braceColor)
    }

    // a lean-to roof slab with real thickness (top, underside, outer fascia,
    // two end caps) rather than a single 2D plane — a flat plane has only
    // one normal direction, so from most viewing angles it reads as a flat
    // dark silhouette; the fascia edge gives it a lit surface and a profile.
    private fun leanToSlab(
        tris: MutableList<RawTri>,
        xIn: Float, yIn: Float, xOut: Float, yOut: Float,
        z0: Float, z1: Float,
        thickness: Float, color: Color
    ) {
        val yInB = yIn - thickness; val yOutB = yOut - thickness
        val ref = Vec3((xIn + xOut) / 2, (yIn + yOut) / 2 - thickness / 2, (z0 + z1) / 2)
        addQuadAuto(tris, Vec3(xIn, yIn, z0), Vec3(xIn, yIn, z1), Vec3(xOut, yOut, z1), Vec3(xOut, yOut, z0), color, ref)
        addQuadAuto(tris, Vec3(xIn, yInB, z1), Vec3(xIn, yInB, z0), Vec3(xOut, yOutB, z0), Vec3(xOut, yOutB, z1), color, ref)
        addQuadAuto(tris, Vec3(xOut, yOut, z0), Vec3(xOut, yOut, z1), Vec3(xOut, yOutB, z1), Vec3(xOut, yOutB, z0), color, ref)
        addQuadAuto(tris, Vec3(xIn, yIn, z0), Vec3(xOut, yOut, z0), Vec3(xOut, yOutB, z0), Vec3(xIn, yInB, z0), color, ref)
        addQuadAuto(tris, Vec3(xOut, yOut, z1), Vec3(xIn, yIn, z1), Vec3(xIn, yInB, z1), Vec3(xOut, yOutB, z1), color, ref)
    }

    // ---------- the "Grand Timber Frame Barn" design: whitewashed board
    // siding, black hardware, an exposed king-post truss, hayloft door,
    // twin hayloft windows, wall sconces, and flanking lean-to porches on
    // both sides. Ported 1:1 from design 01 in barn-timberframe-preview.html.
    // ----------

    private val wallColor = Color(0xFFE4D9BC)
    private val roofColor = Color(0xFF3E3A33)
    private val footColor = Color(0xFF9A9385)
    private val braceColor = Color(0xFF1C1712)
    private val trussColor = Color(0xFF4A3826)
    private val postColor = Color(0xFF7A5A34)
    private val windowColor = Color(0xFF2B2B2B)
    private val sconceColor = braceColor

    private fun buildGrandTimberFrameBarn(): List<RawTri> {
        val tris = mutableListOf<RawTri>()

        val wallW = 3.0f
        val wallH = 1.3f
        val wallD = 2.4f
        val roofProfile = listOf(-1.6f to 0f, -1.15f to 1.2f, 0f to 1.62f, 1.15f to 1.2f, 1.6f to 0f)

        val wallTop = wallH / 2
        val wallBottom = -wallH / 2
        val zFront = wallD / 2

        box(tris, 0f, wallBottom - 0.1f, 0f, wallW + 0.2f, 0.2f, wallD + 0.2f, footColor)
        box(tris, 0f, 0f, 0f, wallW, wallH, wallD, wallColor, skipTop = true)
        gabledRoof(tris, 0f, roofProfile, -wallD / 2, wallD / 2, roofColor, wallColor, Vec3(0f, wallTop * 0.5f, 0f))
        val ridgeY = roofProfile.maxOf { it.second }

        // ground-level cross-braced door
        xBraceDoor(tris, 0f, wallBottom, wallW * 0.42f, wallTop - wallBottom - 0.08f, zFront + 0.001f, wallColor, braceColor)

        // twin hayloft windows
        val winY = wallTop * 0.4f
        val winX = wallW * 0.33f
        panel(tris, -winX, winY, zFront + 0.001f, 0.28f, 0.36f, windowColor)
        panel(tris, winX, winY, zFront + 0.001f, 0.28f, 0.36f, windowColor)
        beam2D(tris, -winX - 0.14f, winY, -winX + 0.14f, winY, zFront + 0.02f, 0.03f, 0.02f, braceColor)
        beam2D(tris, -winX, winY - 0.18f, -winX, winY + 0.18f, zFront + 0.02f, 0.03f, 0.02f, braceColor)
        beam2D(tris, winX - 0.14f, winY, winX + 0.14f, winY, zFront + 0.02f, 0.03f, 0.02f, braceColor)
        beam2D(tris, winX, winY - 0.18f, winX, winY + 0.18f, zFront + 0.02f, 0.03f, 0.02f, braceColor)

        // peaked hayloft door
        xBraceDoor(tris, 0f, wallTop + (ridgeY - wallTop) * 0.18f, 0.5f, 0.55f, zFront + 0.001f, wallColor, braceColor)

        // wall sconces flanking the ground door
        box(tris, -wallW * 0.28f, wallBottom + 0.85f, zFront + 0.06f, 0.05f, 0.14f, 0.05f, sconceColor)
        box(tris, wallW * 0.28f, wallBottom + 0.85f, zFront + 0.06f, 0.05f, 0.14f, 0.05f, sconceColor)

        // exposed king-post truss with a collar tie and knee braces
        val baseY = wallTop + (ridgeY - wallTop) * 0.1f
        val trussZ = zFront + 0.03f
        beam2D(tris, 0f, baseY, 0f, ridgeY - 0.04f, trussZ, 0.09f, 0.07f, trussColor)
        beam2D(tris, 0f, baseY + 0.05f, -0.55f, baseY + 0.05f, trussZ, 0.06f, 0.06f, trussColor)
        beam2D(tris, 0f, baseY + 0.05f, 0.55f, baseY + 0.05f, trussZ, 0.06f, 0.06f, trussColor)
        beam2D(tris, 0f, baseY + 0.22f, -0.9f, baseY - 0.02f, trussZ, 0.07f, 0.06f, trussColor)
        beam2D(tris, 0f, baseY + 0.22f, 0.9f, baseY - 0.02f, trussZ, 0.07f, 0.06f, trussColor)

        // flanking lean-to porches, both sides
        val span = 1.05f; val dropY = 0.55f; val postT = 0.09f
        for (side in intArrayOf(-1, 1)) {
            val xIn = side * wallW / 2
            val xOut = side * (wallW / 2 + span)
            val yIn = wallTop - 0.15f
            val yOut = wallTop - 0.15f - dropY
            leanToSlab(tris, xIn, yIn, xOut, yOut, -wallD / 2, wallD / 2, 0.07f, roofColor)
            val postCy = (wallBottom - 0.1f + yOut) / 2
            val postH = yOut - (wallBottom - 0.1f)
            box(tris, xOut - side * 0.12f, postCy, wallD / 2 - 0.15f, postT, postH, postT, postColor)
            box(tris, xOut - side * 0.12f, postCy, -wallD / 2 + 0.15f, postT, postH, postT, postColor)
        }

        return tris
    }

    // ---------- shared render pipeline: rotate/shade/depth-sort/normalize,
    // identical math to the previous OBJ-based build(), adapted to work on a
    // flat, non-indexed triangle list (procedural builders above never share
    // vertices across triangles the way a parsed mesh does). ----------

    private const val YAW = (Math.PI / 4).toFloat() // 45 degrees
    private const val PITCH = 0.62f
    private const val LIGHT_ANGLE_DEG = 55f

    private fun buildFromRaw(rawTriangles: List<RawTri>): List<BarnTriangle> {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for (t in rawTriangles) {
            for (p in listOf(t.a, t.b, t.c)) {
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.z < minZ) minZ = p.z
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
                if (p.z > maxZ) maxZ = p.z
            }
        }
        val center = Vec3((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f)

        fun toView(p: Vec3): Vec3 {
            val centered = Vec3(p.x - center.x, p.y - center.y, p.z - center.z)
            return rotateX(rotateY(centered, YAW), PITCH)
        }

        val lightRad = (LIGHT_ANGLE_DEG * Math.PI / 180.0).toFloat()
        val lightDir = normalize(Vec3(cos(lightRad), 0.9f, sin(lightRad)))

        data class Shaded(val ra: Vec3, val rb: Vec3, val rc: Vec3, val color: Color, val depth: Float)
        val shadedTriangles = rawTriangles.map { tri ->
            val normal = faceNormal(tri.a, tri.b, tri.c)
            val facing = rotateX(rotateY(normal, YAW), PITCH)
            val brightness = 0.35f + maxOf(0f, dot(facing, lightDir)) * 0.85f
            val ra = toView(tri.a)
            val rb = toView(tri.b)
            val rc = toView(tri.c)
            val depth = (ra.z + rb.z + rc.z) / 3f
            Shaded(ra, rb, rc, shade(tri.color, brightness), depth)
        }

        val sorted = shadedTriangles.sortedBy { it.depth }

        var projMinX = Float.POSITIVE_INFINITY
        var projMaxX = Float.NEGATIVE_INFINITY
        for (t in sorted) {
            for (v in listOf(t.ra, t.rb, t.rc)) {
                if (v.x < projMinX) projMinX = v.x
                if (v.x > projMaxX) projMaxX = v.x
            }
        }
        val projWidth = projMaxX - projMinX
        val footprintUnit = if (projWidth == 0f) 1f else 2.0f / projWidth

        // Anchor point = the rotated projection of "the center of the barn's
        // floor footprint" (mesh-center X/Z, floor-level Y) — see BarnMesh's
        // previous doc for why this, not a naive global min(v.y), is the
        // correct anchor (rotateX mixes height and depth).
        val floorCenterCentered = Vec3(0f, minY - center.y, 0f)
        val anchor = rotateX(rotateY(floorCenterCentered, YAW), PITCH)

        fun normX(v: Vec3) = (v.x - anchor.x) * footprintUnit
        fun normY(v: Vec3) = -(v.y - anchor.y) * footprintUnit

        return sorted.map { tri ->
            BarnTriangle(
                normX0 = normX(tri.ra),
                normY0 = normY(tri.ra),
                normX1 = normX(tri.rb),
                normY1 = normY(tri.rb),
                normX2 = normX(tri.rc),
                normY2 = normY(tri.rc),
                color = tri.color
            )
        }
    }
}
