package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.CLEANER
import com.loadingbyte.cinecred.natives.clippercapi.clippercapi_h.*
import java.awt.BasicStroke
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.PathIterator
import java.awt.geom.Rectangle2D
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.MemorySegment.NULL
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.ref.Cleaner


/** An immutable closed path made up of only linear segments. Suited for various nontrivial path operations. */
class OpPath : AutoCloseable {

    private val pth: MemorySegment
    private val cleanable: Cleaner.Cleanable
    private val windingRule: WindingRule?

    private constructor(pth: MemorySegment, windingRule: WindingRule?) {
        // We use a confined arena since a shared one is surprisingly expensive, and these objects are usually
        // short-lived and only used from a single thread anyway.
        val arena = Arena.ofConfined()
        this.pth = pth.reinterpret(arena, ::Paths64_delete)
        this.cleanable = CLEANER.register(this, CleanerAction(arena))
        this.windingRule = windingRule
    }

    constructor(shape: Shape) {
        // Duplicate code from the above constructor.
        val arena = Arena.ofConfined()
        pth = Paths64_New().reinterpret(arena, ::Paths64_delete)
        cleanable = CLEANER.register(this, CleanerAction(arena))

        val pi = shape.getPathIterator(null, FLATNESS)
        windingRule = if (pi.windingRule == PathIterator.WIND_EVEN_ODD) WindingRule.EVEN_ODD else WindingRule.NON_ZERO
        var points: LongArray? = null
        var pointCountX2 = 0
        var prevPoints: LongArray? = null
        val coords = DoubleArray(2)
        while (!pi.isDone || points != null) {
            val segType = if (!pi.isDone) pi.currentSegment(coords) else PathIterator.SEG_CLOSE
            // Note: If a subpath is not closed, we assume it should be and close it automatically.
            if (points != null && (segType == PathIterator.SEG_CLOSE || segType == PathIterator.SEG_MOVETO)) {
                Arena.ofConfined().use { arena ->
                    val seg = arena.allocate(pointCountX2 * 8L)
                    MemorySegment.copy(points, 0, seg, JAVA_LONG, 0, pointCountX2)
                    Paths64_addPath(pth, seg, pointCountX2 / 2)
                }
                prevPoints = points
                points = null
                pointCountX2 = 0
            }
            if (segType == PathIterator.SEG_MOVETO || segType == PathIterator.SEG_LINETO) {
                if (points == null) {
                    points = LongArray(512)
                    // If a subpath starts with LINETO, do what Java2D does and use the previous subpath's first (and
                    // through closing also last) point as the new subpath's first point.
                    if (segType == PathIterator.SEG_LINETO && prevPoints != null) {
                        points[pointCountX2++] = prevPoints[0]
                        points[pointCountX2++] = prevPoints[1]
                    }
                }
                if (pointCountX2 + 2 > points.size)
                    points = points.copyOf(points.size * 2)
                points[pointCountX2++] = (coords[0] * U).toLong()
                points[pointCountX2++] = (coords[1] * U).toLong()
            }
            if (!pi.isDone)
                pi.next()
        }
    }

    override fun close() {
        cleanable.clean()
    }

    fun toPath(): Path2D.Double {
        val pathCount = Paths64_pathCount(pth)
        var capacity = 0
        for (pathIdx in 0..<pathCount)
            capacity += 1 + Paths64_pathPointCount(pth, pathIdx)
        val rule = if (windingRule == WindingRule.EVEN_ODD) PathIterator.WIND_EVEN_ODD else PathIterator.WIND_NON_ZERO
        val shape = Path2D.Double(rule, capacity)
        for (pathIdx in 0..<pathCount) {
            val pointCount = Paths64_pathPointCount(pth, pathIdx)
            val seg = Paths64_pathPoints(pth, pathIdx).reinterpret(pointCount * 16L)
            for (pointIdx in 0L..<pointCount) {
                val x = seg.get(JAVA_LONG, pointIdx * 16) * IU
                val y = seg.get(JAVA_LONG, pointIdx * 16 + 8) * IU
                if (pointIdx == 0L)
                    shape.moveTo(x, y)
                else
                    shape.lineTo(x, y)
            }
            if (pointCount > 0)
                shape.closePath()
        }
        return shape
    }

    fun transform(transform: AffineTransform?): OpPath {
        if (transform == null || transform.isIdentity)
            return this
        val res = OpPath(Paths64_New(), windingRule)
        if (transform.type == AffineTransform.TYPE_TRANSLATION)
            Clipper_Translate(pth, (transform.translateX * U).toLong(), (transform.translateY * U).toLong(), res.pth)
        else {
            val shift = 10
            val scale = 1 shl shift
            Clipper_Transform(
                pth, shift,
                (transform.scaleX * scale).toLong(), (transform.shearY * scale).toLong(),
                (transform.shearX * scale).toLong(), (transform.scaleY * scale).toLong(),
                (transform.translateX * U).toLong(), (transform.translateY * U).toLong(),
                res.pth
            )
        }
        return res
    }

    /** Removes points that are less than an epsilon distance from an imaginary line passing through its 2 neighbors. */
    fun simplify(): OpPath =
        OpPath(Clipper_Simplify(pth, SIMPLI_EPS, true), windingRule)

    /** Returns a path that doesn't have self-intersections or unnecessary overlap, and is agnostic to winding rule. */
    fun clean(): OpPath {
        val fillRule = (windingRule ?: WindingRule.NON_ZERO).code
        val res = OpPath(Paths64_New(), windingRule = null /* irrelevant */)
        Clipper_BooleanOp(pth, NULL, NULL, ClipType_Union(), fillRule, false, false, res.pth, NULL)
        return res
    }

    fun intersect(rect: Rectangle2D): OpPath {
        val pth1 = Clipper_IntersectClosedWithRect(
            pth,
            (rect.minX * U).toLong(), (rect.minY * U).toLong(), (rect.maxX * U).toLong(), (rect.maxY * U).toLong()
        )
        return OpPath(pth1, windingRule)
    }

    fun union(other: OpPath): OpPath = booleanOp(other, ClipType_Union())
    fun intersect(other: OpPath): OpPath = booleanOp(other, ClipType_Intersection())
    fun difference(other: OpPath): OpPath = booleanOp(other, ClipType_Difference())
    fun xor(other: OpPath): OpPath = booleanOp(other, ClipType_Xor())

    fun dilate(delta: Double, join: Int, miterLimit: Double = 10.0): OpPath =
        offset(delta, join, EndType_Polygon(), miterLimit)

    fun stroke(delta: Double, join: Int, miterLimit: Double = 10.0): OpPath =
        offset(delta, join, EndType_Joined(), miterLimit)

    private fun booleanOp(other: OpPath, clipType: Int): OpPath {
        var pth1: MemorySegment? = null
        try {
            val fillRule: Int
            if (windingRule == null || other.windingRule == null || windingRule == other.windingRule)
                fillRule = (windingRule ?: other.windingRule ?: WindingRule.NON_ZERO).code
            else {
                // If the two paths have different non-irrelevant winding rules, apply the union op to one of them to
                // make its winding rule irrelevant.
                pth1 = Paths64_New()
                Clipper_BooleanOp(pth, NULL, NULL, ClipType_Union(), windingRule.code, false, false, pth1, NULL)
                fillRule = other.windingRule.code
            }
            val res = OpPath(Paths64_New(), windingRule = null /* irrelevant */)
            Clipper_BooleanOp(pth1 ?: pth, NULL, other.pth, clipType, fillRule, false, false, res.pth, NULL)
            return res
        } finally {
            pth1?.let(::Paths64_delete)
        }
    }

    private fun offset(delta: Double, basicStrokeJoin: Int, endType: Int, miterLimit: Double): OpPath {
        val joinType = when (basicStrokeJoin) {
            BasicStroke.JOIN_MITER -> JoinType_Miter()
            BasicStroke.JOIN_ROUND -> JoinType_Round()
            BasicStroke.JOIN_BEVEL -> JoinType_Bevel()
            else -> throw IllegalArgumentException()
        }
        val pth1 = Paths64_New()
        var pth2: MemorySegment? = null
        try {
            // We prepare the path by cleaning and then simplifying it, as recommended by this page:
            // https://www.angusj.com/clipper2/Docs/Units/Clipper.Offset/Classes/ClipperOffset/_Body.htm
            // Notice that we always apply a union op to the path beforehand, irrespective of whether a BooleanOp has
            // previously been applied to the path. That's because BooleanOp can occasionally return solutions with
            // touching polygons, as is stated in the "Clipping closed paths" section on this page:
            // https://www.angusj.com/clipper2/Docs/Overview.htm
            val fillRule = (windingRule ?: WindingRule.NON_ZERO).code
            Clipper_BooleanOp(pth, NULL, NULL, ClipType_Union(), fillRule, false, false, pth1, NULL)
            pth2 = Clipper_Simplify(pth1, SIMPLI_EPS, true)
            val res = OpPath(Paths64_New(), windingRule = null /* irrelevant due to the union above */)
            Clipper_Offset(pth2, delta * U, joinType, endType, miterLimit * U, FLATNESS * U, false, false, res.pth)
            return res
        } finally {
            pth1.let(::Paths64_delete)
            pth2?.let(::Paths64_delete)
        }
    }


    companion object {
        // The Clipper discretization unit.
        private const val U = (1 shl 16).toDouble()
        private const val IU = 1.0 / U
        // The maximum distance between a curve and its flattened (linearized) equivalent.
        private const val FLATNESS = 0.05
        // The minimum distance of a point from the imaginary line between its neighbors.
        // While this is not directly related to flatness, it is at least related to the flatness's scale.
        private const val SIMPLI_EPS = FLATNESS * 0.5 * U
    }


    enum class WindingRule(val code: Int) {
        EVEN_ODD(FillRule_EvenOdd()),
        NON_ZERO(FillRule_NonZero()),
    }


    private class CleanerAction(private val arena: Arena) : Runnable {
        override fun run() {
            arena.close()
        }
    }

}
