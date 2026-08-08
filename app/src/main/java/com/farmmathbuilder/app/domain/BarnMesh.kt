package com.farmmathbuilder.app.domain

import android.content.Context
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
 * Parses `barn.obj` (a real Wings3D-exported mesh, no material/texture file —
 * color comes from the object group name) once, rotates/shades/depth-sorts it
 * with a fixed camera, and caches the result as a flat list of screen-ready
 * [BarnTriangle]s. All math (parseObj, rotateY, rotateX, faceNormal,
 * normalize, dot, shade, GROUP_COLOR) is ported 1:1 from the validated
 * reference implementation at docs/previews/barn-mesh-preview.html — this is
 * the source of truth, not reinvented here.
 *
 * The whole computation runs exactly once (cached in [triangles], a `by
 * lazy` singleton) — never per-frame or per-recomposition. Only the cheap
 * final step (mapping normX/normY to screen pixels via tileW*scale) happens
 * at draw time, in FarmGridCanvas.
 */
object BarnMesh {

    private var cached: List<BarnTriangle>? = null

    fun load(context: Context): List<BarnTriangle> {
        cached?.let { return it }
        val text = context.assets.open("barn.obj").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val result = build(text)
        cached = result
        return result
    }

    private val GROUP_COLOR: Map<String, Color> = mapOf(
        "Barn" to Color(0xFFE0CBA0),
        "Roof" to Color(0xFF689F38),
        "Vane" to Color(0xFF4E342E)
    )
    private val FALLBACK_COLOR = Color(0xFF9E9E9E)

    private fun colorFor(name: String): Color = GROUP_COLOR[name] ?: FALLBACK_COLOR

    private data class Group(val name: String, val faces: MutableList<IntArray> = mutableListOf())

    private data class Vec3(val x: Float, val y: Float, val z: Float)

    private fun parseObj(text: String): Pair<List<Vec3>, List<Group>> {
        val positions = mutableListOf<Vec3>()
        val groups = mutableListOf<Group>()
        var current = Group("default")
        groups.add(current)
        for (raw in text.split('\n')) {
            val line = raw.trim()
            when {
                line.startsWith("v ") -> {
                    val parts = line.split(Regex("\\s+")).drop(1).map { it.toFloat() }
                    positions.add(Vec3(parts[0], parts[1], parts[2]))
                }
                line.startsWith("o ") -> {
                    current = Group(line.substring(2).trim())
                    groups.add(current)
                }
                line.startsWith("f ") -> {
                    val idx = line.split(Regex("\\s+")).drop(1).map { tok ->
                        tok.split('/')[0].toInt() - 1
                    }
                    // Fan-triangulate n-gons, exactly like the preview.
                    for (i in 1 until idx.size - 1) {
                        current.faces.add(intArrayOf(idx[0], idx[i], idx[i + 1]))
                    }
                }
            }
        }
        return positions to groups.filter { it.faces.isNotEmpty() }
    }

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

    private fun normalize(v: Vec3): Vec3 {
        val len = hypot(hypot(v.x.toDouble(), v.y.toDouble()), v.z.toDouble()).toFloat().let { if (it == 0f) 1f else it }
        return Vec3(v.x / len, v.y / len, v.z / len)
    }

    private fun faceNormal(a: Vec3, b: Vec3, c: Vec3): Vec3 {
        val u = Vec3(b.x - a.x, b.y - a.y, b.z - a.z)
        val v = Vec3(c.x - a.x, c.y - a.y, c.z - a.z)
        val n = Vec3(
            u.y * v.z - u.z * v.y,
            u.z * v.x - u.x * v.z,
            u.x * v.y - u.y * v.x
        )
        return normalize(n)
    }

    private fun dot(a: Vec3, b: Vec3): Float = a.x * b.x + a.y * b.y + a.z * b.z

    private fun shade(color: Color, brightness: Float): Color {
        val b = brightness.coerceIn(0.28f, 1.15f)
        val r = (color.red * b).coerceIn(0f, 1f)
        val g = (color.green * b).coerceIn(0f, 1f)
        val bl = (color.blue * b).coerceIn(0f, 1f)
        return Color(r, g, bl, 1f)
    }

    private const val YAW = (Math.PI / 4).toFloat() // 45 degrees
    private const val PITCH = 0.62f
    private const val LIGHT_ANGLE_DEG = 55f

    private fun build(text: String): List<BarnTriangle> {
        val (positions, groups) = parseObj(text)

        // Flatten all triangles across groups with their base (unshaded) color,
        // same as the preview's `triangles` array.
        data class RawTri(val a: Int, val b: Int, val c: Int, val color: Color)
        val rawTriangles = mutableListOf<RawTri>()
        for (g in groups) {
            val color = colorFor(g.name)
            for (f in g.faces) {
                rawTriangles.add(RawTri(f[0], f[1], f[2], color))
            }
        }

        // Bounding box + center, same as the preview.
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for (p in positions) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.z < minZ) minZ = p.z
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
            if (p.z > maxZ) maxZ = p.z
        }
        val center = Vec3((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f)

        // Rotate every vertex to view-space (mesh-centered, rotateY then rotateX
        // — same order as the preview's `project`), keeping (vx, vy, vz).
        val rotated = positions.map { p ->
            val centered = Vec3(p.x - center.x, p.y - center.y, p.z - center.z)
            rotateX(rotateY(centered, YAW), PITCH)
        }

        // Lighting: same convention as the preview's lightAngle-driven lightDir.
        val lightRad = (LIGHT_ANGLE_DEG * Math.PI / 180.0).toFloat()
        val lightDir = normalize(Vec3(cos(lightRad), 0.9f, sin(lightRad)))

        // Shade + depth using real (unrotated) positions for the face normal,
        // then rotate the normal the same way as the preview does.
        data class Shaded(val a: Int, val b: Int, val c: Int, val color: Color, val depth: Float)
        val shadedTriangles = rawTriangles.map { tri ->
            val va = positions[tri.a]
            val vb = positions[tri.b]
            val vc = positions[tri.c]
            val normal = faceNormal(va, vb, vc)
            val facing = rotateX(rotateY(normal, YAW), PITCH)
            val brightness = 0.35f + maxOf(0f, dot(facing, lightDir)) * 0.85f
            val ra = rotated[tri.a]
            val rb = rotated[tri.b]
            val rc = rotated[tri.c]
            val depth = (ra.z + rb.z + rc.z) / 3f
            Shaded(tri.a, tri.b, tri.c, shade(tri.color, brightness), depth)
        }

        // Depth sort ascending (back-to-front painter's algorithm), same
        // direction as the preview's `drawList.sort((x,y)=>x.depth-y.depth)`.
        val sorted = shadedTriangles.sortedBy { it.depth }

        // Projected bounding box over all vx (used only for width -> scale,
        // not for anchoring — see below).
        var projMinX = Float.POSITIVE_INFINITY
        var projMaxX = Float.NEGATIVE_INFINITY
        for (v in rotated) {
            if (v.x < projMinX) projMinX = v.x
            if (v.x > projMaxX) projMaxX = v.x
        }
        val projWidth = projMaxX - projMinX
        val footprintUnit = if (projWidth == 0f) 1f else 2.0f / projWidth

        // Anchor point = the rotated projection of "the center of the barn's
        // floor footprint" (mesh-center X/Z, floor-level Y), NOT the global
        // min(v.y) across every rotated vertex. The naive global-min approach
        // (previous implementation) is unreliable: rotateX mixes height and
        // depth, so a roof vertex far along Z can project to a *smaller* view
        // -space Y than any actual floor vertex, making the anchor sit above
        // the true floor and the whole barn appear to float above the tile
        // plane (founder-reported bug). Anchoring to a single well-defined 3D
        // point — rotated through the exact same transform as every other
        // vertex — always lands on the true floor-center regardless of the
        // mesh's Z extent.
        val floorCenterCentered = Vec3(0f, minY - center.y, 0f)
        val anchor = rotateX(rotateY(floorCenterCentered, YAW), PITCH)

        fun normX(v: Vec3) = (v.x - anchor.x) * footprintUnit
        fun normY(v: Vec3) = -(v.y - anchor.y) * footprintUnit

        return sorted.map { tri ->
            val va = rotated[tri.a]
            val vb = rotated[tri.b]
            val vc = rotated[tri.c]
            BarnTriangle(
                normX0 = normX(va),
                normY0 = normY(va),
                normX1 = normX(vb),
                normY1 = normY(vb),
                normX2 = normX(vc),
                normY2 = normY(vc),
                color = tri.color
            )
        }
    }
}
