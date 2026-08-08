package com.farmmathbuilder.app.domain

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A single precomputed, screen-ready triangle of one fence module orientation:
 * normalized (tile-footprint-relative) X/Y offsets for its 3 vertices, plus
 * its flat-shaded color. Depth sorting has already been applied when the
 * list is built (see [FenceMesh.load]), so triangles are stored in the
 * correct back-to-front paint order and can be drawn top-to-bottom as-is.
 */
data class FenceTriangle(
    val normX0: Float,
    val normY0: Float,
    val normX1: Float,
    val normY1: Float,
    val normX2: Float,
    val normY2: Float,
    val color: Color
)

/**
 * The fence module needs two precomputed orientations to line all four sides
 * of the square buildable boundary: one for edges that vary by column
 * (top/bottom of the boundary ring) and one for edges that vary by row
 * (left/right of the ring). Both are built from the exact same mesh/camera
 * pipeline, 90 degrees of yaw apart (see [FenceMesh.load]).
 */
data class FenceVariants(
    val alongColumns: List<FenceTriangle>,
    val alongRows: List<FenceTriangle>
)

/**
 * Parses `fence.fbx` (a real Blender-exported ASCII FBX 6.1 mesh containing
 * two named parts — `Plane`, a diagonal cross-brace/rail, and `Cube`, a run
 * of baked-in vertical pickets — no material/texture file, so color is
 * assigned by part name) once, builds two camera-oriented variants (90
 * degrees of yaw apart, so one naturally lines column-running boundary edges
 * and the other row-running edges), and caches the result.
 *
 * All parsing/projection/shading math (extractBlock, parseFloatList,
 * parseModel, rotateXYZ, toWorld, moduleTriangles, rotateY/rotateXView,
 * faceNormal/normalize/dot/shade) is ported 1:1 from the validated reference
 * implementation at docs/previews/fence-preview.html — this is the source of
 * truth, not reinvented here. Camera/light numbers (yaw, pitch, light angle,
 * brightness formula) are reused verbatim from [BarnMesh] for visual
 * consistency between the two meshes in-game (the preview's own light vector
 * was for its free-look demo only).
 *
 * Anchoring follows the same fix [BarnMesh] required: each variant anchors
 * to the rotated projection of the module's actual floor-center point
 * (`Vec3(0, minY - center.y, 0)` in mesh-centered space), never to the
 * global minimum of all rotated vertices' Y (that approach is unreliable —
 * see [BarnMesh]'s doc comment for why).
 */
object FenceMesh {

    private var cached: FenceVariants? = null

    fun load(context: Context): FenceVariants {
        cached?.let { return it }
        val text = context.assets.open("fence.fbx").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val result = build(text)
        cached = result
        return result
    }

    private data class Vec3(val x: Float, val y: Float, val z: Float) {
        operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
        operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    }

    private data class ModelData(
        val positions: List<Vec3>,
        val faces: List<IntArray>,
        val translation: Vec3,
        val rotationDeg: Vec3,
        val scaling: Vec3
    )

    private data class RawTri(val a: Vec3, val b: Vec3, val c: Vec3, val tag: String)

    // ---------- Minimal ASCII FBX 6.1 parser (ported from the preview) ----------

    private fun extractBlock(text: String, startMarker: String, endMarker: String): String {
        val start = text.indexOf(startMarker)
        if (start == -1) return ""
        val from = start + startMarker.length
        val end = text.indexOf(endMarker, from)
        return if (end == -1) text.substring(from) else text.substring(from, end)
    }

    private fun parseFloatList(raw: String): List<Float> =
        raw.replace(Regex("\\s+"), "").split(',').filter { it.isNotEmpty() }.map { it.toFloat() }

    private fun parseVec3Property(window: String, propName: String): Vec3 {
        val re = Regex("Property: \"$propName\"[^\\n]*\"A\\+?\",([^\\n]+)")
        val m = re.find(window) ?: return Vec3(0f, 0f, 0f)
        val parts = m.groupValues[1].split(',').map { it.trim().toFloat() }
        return Vec3(parts[0], parts[1], parts[2])
    }

    private fun parseModel(text: String, modelName: String): ModelData {
        val marker = "Model: \"Model::$modelName\", \"Mesh\" {"
        val blockStart = text.indexOf(marker)
        require(blockStart != -1) { "Model not found: $modelName" }
        // Scope the search to a reasonable window after the marker so we don't
        // accidentally grab a later duplicate/reference block's fields.
        val window = text.substring(blockStart, minOf(text.length, blockStart + 20000))

        val translation = parseVec3Property(window, "Lcl Translation")
        val rotationDeg = parseVec3Property(window, "Lcl Rotation")
        val scaling = parseVec3Property(window, "Lcl Scaling")

        val verticesRaw = extractBlock(window, "Vertices: ", "PolygonVertexIndex:")
        val positionsFlat = parseFloatList(verticesRaw)
        val positions = mutableListOf<Vec3>()
        var i = 0
        while (i + 2 < positionsFlat.size) {
            positions.add(Vec3(positionsFlat[i], positionsFlat[i + 1], positionsFlat[i + 2]))
            i += 3
        }

        val pviRaw = extractBlock(window, "PolygonVertexIndex: ", "GeometryVersion:")
        val pvi = parseFloatList(pviRaw).map { it.roundToInt() }
        val faces = mutableListOf<IntArray>()
        var current = mutableListOf<Int>()
        for (raw in pvi) {
            if (raw < 0) {
                current.add(-raw - 1)
                faces.add(current.toIntArray())
                current = mutableListOf()
            } else {
                current.add(raw)
            }
        }

        return ModelData(positions, faces, translation, rotationDeg, scaling)
    }

    private fun degToRad(d: Float): Float = (d * Math.PI / 180.0).toFloat()

    /** Blender/FBX ASCII 6.1 default Euler order: apply X, then Y, then Z. */
    private fun rotateXYZ(p: Vec3, deg: Vec3): Vec3 {
        var v = p
        val ax = degToRad(deg.x)
        val ay = degToRad(deg.y)
        val az = degToRad(deg.z)
        // rotate X
        v = Vec3(v.x, v.y * cos(ax) - v.z * sin(ax), v.y * sin(ax) + v.z * cos(ax))
        // rotate Y
        v = Vec3(v.x * cos(ay) + v.z * sin(ay), v.y, -v.x * sin(ay) + v.z * cos(ay))
        // rotate Z
        v = Vec3(v.x * cos(az) - v.y * sin(az), v.x * sin(az) + v.y * cos(az), v.z)
        return v
    }

    private fun toWorld(local: Vec3, model: ModelData): Vec3 {
        val scaled = Vec3(local.x * model.scaling.x, local.y * model.scaling.y, local.z * model.scaling.z)
        val rotated = rotateXYZ(scaled, model.rotationDeg)
        return rotated + model.translation
    }

    private fun moduleTriangles(model: ModelData, tag: String): List<RawTri> {
        val world = model.positions.map { toWorld(it, model) }
        val tris = mutableListOf<RawTri>()
        for (face in model.faces) {
            for (i in 1 until face.size - 1) {
                tris.add(RawTri(world[face[0]], world[face[i]], world[face[i + 1]], tag))
            }
        }
        return tris
    }

    // ---------- Camera / shading (numbers reused verbatim from BarnMesh) ----------

    private fun rotateY(p: Vec3, a: Float): Vec3 {
        val cosA = cos(a)
        val sinA = sin(a)
        return Vec3(p.x * cosA + p.z * sinA, p.y, -p.x * sinA + p.z * cosA)
    }

    private fun rotateXView(p: Vec3, a: Float): Vec3 {
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

    private fun colorFor(tag: String): Color = when (tag) {
        "brace" -> Color(0xFFC9A676)
        "picket" -> Color(0xFFD8BC8E)
        else -> Color(0xFF9E9E9E)
    }

    private fun shade(color: Color, brightness: Float): Color {
        val b = brightness.coerceIn(0.28f, 1.15f)
        val r = (color.red * b).coerceIn(0f, 1f)
        val g = (color.green * b).coerceIn(0f, 1f)
        val bl = (color.blue * b).coerceIn(0f, 1f)
        return Color(r, g, bl, 1f)
    }

    private val YAW = (Math.PI / 4).toFloat() // 45 degrees
    private const val PITCH = 0.62f
    private const val LIGHT_ANGLE_DEG = 55f

    private fun buildVariant(baseTriangles: List<RawTri>, yaw: Float): List<FenceTriangle> {
        // Bounding box + center over the combined (both parts) world-space mesh.
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for (t in baseTriangles) {
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

        val lightRad = (LIGHT_ANGLE_DEG * Math.PI / 180.0).toFloat()
        val lightDir = normalize(Vec3(cos(lightRad), 0.9f, sin(lightRad)))

        data class Shaded(val ra: Vec3, val rb: Vec3, val rc: Vec3, val color: Color, val depth: Float)

        fun toView(p: Vec3): Vec3 {
            val centered = p - center
            return rotateXView(rotateY(centered, yaw), PITCH)
        }

        val shadedTriangles = baseTriangles.map { tri ->
            val normal = faceNormal(tri.a, tri.b, tri.c)
            val facing = rotateXView(rotateY(normal, yaw), PITCH)
            val brightness = 0.35f + maxOf(0f, dot(facing, lightDir)) * 0.85f
            val ra = toView(tri.a)
            val rb = toView(tri.b)
            val rc = toView(tri.c)
            val depth = (ra.z + rb.z + rc.z) / 3f
            Shaded(ra, rb, rc, shade(colorFor(tri.tag), brightness), depth)
        }

        // Depth sort ascending (back-to-front painter's algorithm), same
        // direction as BarnMesh / the preview.
        val sorted = shadedTriangles.sortedBy { it.depth }

        // Projected bounding box over all view-space vx (used only for width ->
        // scale, not for anchoring).
        var projMinX = Float.POSITIVE_INFINITY
        var projMaxX = Float.NEGATIVE_INFINITY
        for (s in sorted) {
            for (v in listOf(s.ra, s.rb, s.rc)) {
                if (v.x < projMinX) projMinX = v.x
                if (v.x > projMaxX) projMaxX = v.x
            }
        }
        val projWidth = projMaxX - projMinX
        // One fence module renders per single boundary grid cell (unlike the
        // barn's 2x2 footprint), so normalize to exactly 1 tile-width.
        val footprintUnit = if (projWidth == 0f) 1f else 1.0f / projWidth

        // Anchor point = the rotated projection of "the center of the module's
        // floor footprint" (mesh-center X/Z, floor-level Y), NOT the global
        // min(v.y) across every rotated vertex — see BarnMesh's doc comment for
        // why the global-min approach is unreliable (rotateX mixes height and
        // depth, so a vertex far along Z can project to a smaller view-space Y
        // than any actual floor vertex).
        val floorCenterCentered = Vec3(0f, minY - center.y, 0f)
        val anchor = rotateXView(rotateY(floorCenterCentered, yaw), PITCH)

        fun normX(v: Vec3) = (v.x - anchor.x) * footprintUnit
        fun normY(v: Vec3) = -(v.y - anchor.y) * footprintUnit

        return sorted.map { s ->
            FenceTriangle(
                normX0 = normX(s.ra),
                normY0 = normY(s.ra),
                normX1 = normX(s.rb),
                normY1 = normY(s.rb),
                normX2 = normX(s.rc),
                normY2 = normY(s.rc),
                color = s.color
            )
        }
    }

    private fun build(text: String): FenceVariants {
        val planeModel = parseModel(text, "Plane")
        val cubeModel = parseModel(text, "Cube")

        val baseTriangles = moduleTriangles(planeModel, "brace") + moduleTriangles(cubeModel, "picket")

        // Two orientations, 90 degrees of yaw apart: the fixed camera's yaw is
        // exactly the grid's own isometric 45-degree convention, so building
        // the whole pipeline twice (same pitch/light/anchoring/everything else)
        // gives the two directions needed to line all four sides of the square
        // boundary "for free".
        val alongColumns = buildVariant(baseTriangles, YAW)
        val alongRows = buildVariant(baseTriangles, YAW + (PI / 2).toFloat())

        return FenceVariants(alongColumns = alongColumns, alongRows = alongRows)
    }
}
