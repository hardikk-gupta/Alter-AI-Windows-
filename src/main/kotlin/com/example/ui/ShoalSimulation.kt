package com.example.ui

import kotlin.math.*
import kotlin.random.Random

internal class ShoalFood(var x: Float, var y: Float, var age: Float = 0f,
    var consumed: Boolean = false, var fishId: Int = -1)

internal enum class RiverSpecies { KOI, HAMMERHEAD, DOLPHIN }

internal enum class ShoalMode { IDLE, TYPING, BURST, RECOVERY }
internal class ShoalFish(val id: Int, var x: Float, var y: Float, var vx: Float, var vy: Float,
    var length: Float, var phase: Float) {
    // Stable by actor ID so existing v4–v6 snapshots need no schema migration.
    val species = when (id % 6) {
        2 -> RiverSpecies.HAMMERHEAD
        4 -> RiverSpecies.DOLPHIN
        else -> RiverSpecies.KOI
    }
    val bodyX = FloatArray(4)
    val bodyY = FloatArray(4)
    val bodyRadius = FloatArray(4)
    var heading = atan2(vy, vx)
    var turnRate = 0f

    // Derived render geometry: fixed-length links, not independently displaced body columns.
    val spineX = FloatArray(25)
    val spineY = FloatArray(25)
    val spineAngle = FloatArray(25)

    fun poseSpine(renderedLength: Float) {
        val step = 1.38f / (spineX.size - 1)
        val speed = hypot(vx, vy)
        val curvature = if (speed > .001f) (-turnRate * renderedLength / speed).coerceIn(-.75f, .75f) else 0f
        spineX[0] = .48f; spineY[0] = 0f; spineAngle[0] = 0f
        for (i in 1 until spineX.size) {
            val distance = i * step
            // Head leads a turn; progressively older body headings trail behind it.
            val angle = curvature * (distance - .24f).coerceAtLeast(0f)
            val tangent = (spineAngle[i - 1] + angle) * .5f
            spineX[i] = spineX[i - 1] - cos(tangent) * step
            spineY[i] = spineY[i - 1] - sin(tangent) * step
            spineAngle[i] = angle
        }
    }

}

/** A bounded offscreen pool, viewed through the phone: visible population is never fixed. */
internal class ShoalSimulation(val fishCount: Int = 18, seed: Int = Random.nextInt()) {
    init { require(fishCount in 1..32) }
    var width = 400f; private set
    var height = 700f; private set
    var time = 0f; private set
    var mode = ShoalMode.IDLE; private set
    var modeAge = 0f; private set
    var typing = false; private set
    var foodX = 0f; private set
    var foodY = 0f; private set
    var foodAge = 3f; private set
    private var left = 0f; private var top = 0f; private var right = 0f; private var bottom = 0f
    private val foodResponders = intArrayOf(-1, -1) // Legacy last-tap snapshot fields.
    val food = mutableListOf<ShoalFood>()
    fun headX(f: ShoalFish) = f.x + cos(f.heading) * visibleLength(f) * .48f
    fun headY(f: ShoalFish) = f.y + sin(f.heading) * visibleLength(f) * .48f
    private fun nearestFeeder(x: Float, y: Float) = fish.filter(::isVisible)
        .filter { hypot(headX(it) - x, headY(it) - y) <= 480f * sizeScale }
        .minByOrNull { hypot(headX(it) - x, headY(it) - y) }?.id ?: -1
    val sizeScale get() = (min(width, height) / 375f).coerceIn(.35f, 2f)
    val worldMargin get() = maxOf(160f, min(width, height) * 1.05f) * maxOf(1f, sqrt(fishCount / 18f))
    fun radius(f: ShoalFish) = (f.length * 1.03f + 5f) * sizeScale
    fun visibleLength(f: ShoalFish) = f.length * sizeScale
    fun cruiseSpeed(f: ShoalFish) = (54f + (132f - f.length) * .9f).coerceIn(54f, 84f) * sizeScale
    fun isVisible(f: ShoalFish): Boolean {
        val r = radius(f)
        return f.x + r > 0f && f.x - r < width && f.y + r > 0f && f.y - r < height
    }
    private val headings = FloatArray(fishCount)
    private val random = Random(seed)
    val fish = List(fishCount) { id ->
        val angle = random.nextFloat() * 2 * PI.toFloat()
        ShoalFish(id, 0f, 0f, cos(angle) * 72f, sin(angle) * 72f,
            96f + random.nextFloat() * 36f, random.nextFloat() * 2 * PI.toFloat())
    }
    init { fish.forEachIndexed { i, f -> place(f, fish.take(i), random, offscreen = false) } }

    private fun place(f: ShoalFish, others: List<ShoalFish>, random: Random, offscreen: Boolean) {
        // Rejection placement never introduces a formation. The enlarged world keeps density low.
        repeat(2048) {
            val x = -worldMargin + random.nextFloat() * (width + 2 * worldMargin)
            val y = -worldMargin + random.nextFloat() * (height + 2 * worldMargin)
            val r = radius(f)
            if (offscreen && x + r > 0 && x - r < width && y + r > 0 && y - r < height) return@repeat
            if (others.all { it === f || hypot(x - it.x, y - it.y) > r + radius(it) + 4f }) {
                f.x = x; f.y = y
                if (!isVisible(f)) {
                    // Each entering fish follows its own crossing, not a shared flock target.
                    f.heading = atan2(random.nextFloat() * height - y, random.nextFloat() * width - x)
                    f.turnRate = 0f
                    val speed = hypot(f.vx, f.vy)
                    f.vx = cos(f.heading) * speed; f.vy = sin(f.heading) * speed
                }
                return
            }
        }
        // Extremely damaged/overcrowded restored states recover outside the visible frame only.
        f.x = -worldMargin - radius(f) * (f.id + 2) * 3
        f.y = -worldMargin
    }

    private val bodyStations = floatArrayOf(.34f, .05f, -.30f, -.75f)
    private val bodyWidths = floatArrayOf(.20f, .36f, .28f, .27f)
    private fun prepareBodies() {
        fish.forEach { f ->
            val length = visibleLength(f)
            f.poseSpine(length)
            for (i in bodyStations.indices) {
                val segment = ((.48f - bodyStations[i]) / 1.38f * 24).coerceIn(0f, 23.999f)
                val at = segment.toInt(); val mix = segment - at
                val x = (f.spineX[at] + (f.spineX[at + 1] - f.spineX[at]) * mix) * length
                val y = (f.spineY[at] + (f.spineY[at + 1] - f.spineY[at]) * mix) * length
                f.bodyX[i] = cos(f.heading) * x - sin(f.heading) * y
                f.bodyY[i] = sin(f.heading) * x + cos(f.heading) * y
                f.bodyRadius[i] = bodyWidths[i] * length + 11f * sizeScale
            }
            // The wide hammerhead needs more lateral clearance.
            if (f.species == RiverSpecies.HAMMERHEAD) f.bodyRadius[0] = .43f * length + 11f * sizeScale
        }
    }

    fun bodyClearance(a: ShoalFish, b: ShoalFish, ahead: Float = 0f): Float {
        var gap = Float.POSITIVE_INFINITY
        for (i in a.bodyX.indices) for (j in b.bodyX.indices) {
            val dx = b.x + b.bodyX[j] - a.x - a.bodyX[i] + (b.vx - a.vx) * ahead
            val dy = b.y + b.bodyY[j] - a.y - a.bodyY[i] + (b.vy - a.vy) * ahead
            gap = minOf(gap, hypot(dx, dy) - a.bodyRadius[i] - b.bodyRadius[j])
        }
        return gap
    }

    private fun separateFish() {
        prepareBodies()
        // Bounded shape-disc solver. Whole-fish circles created invisible walls that cancelled
        // forward motion long before the actual bodies were close. Allow rare contact chains
        // to settle before offscreen recovery; ordinary frames exit on the first clear pass.
        repeat(128) {
            var overlap = false
            for (i in fish.indices) for (j in i + 1 until fish.size) {
                val a = fish[i]; val b = fish[j]
                var depth = 0f; var pushX = 0f; var pushY = 0f
                for (ai in a.bodyX.indices) for (bi in b.bodyX.indices) {
                    var dx = b.x + b.bodyX[bi] - a.x - a.bodyX[ai]
                    var dy = b.y + b.bodyY[bi] - a.y - a.bodyY[ai]
                    val d = hypot(dx, dy)
                    val penetration = a.bodyRadius[ai] + b.bodyRadius[bi] - d
                    if (penetration > depth) {
                        if (d < .001f) { dx = cos(a.phase + j); dy = sin(a.phase + j) }
                        val norm = hypot(dx, dy).coerceAtLeast(.001f)
                        depth = penetration; pushX = dx / norm; pushY = dy / norm
                    }
                }
                if (depth > 0f) {
                    overlap = true
                    // A contact correction may move sideways/forward, never backwards against
                    // cruising. Otherwise a solver can quietly pin a swimmer at a food pellet.
                    var ax = -pushX; var ay = -pushY
                    var bx = pushX; var by = pushY
                    val aBack = minOf(0f, ax * cos(a.heading) + ay * sin(a.heading))
                    val bBack = minOf(0f, bx * cos(b.heading) + by * sin(b.heading))
                    ax -= aBack * cos(a.heading); ay -= aBack * sin(a.heading)
                    bx -= bBack * cos(b.heading); by -= bBack * sin(b.heading)
                    val gain = (-ax * pushX - ay * pushY + bx * pushX + by * pushY)
                    if (gain > .05f) {
                        val push = (depth + .002f) / gain
                        a.x += ax * push; a.y += ay * push
                        b.x += bx * push; b.y += by * push
                    } else {
                        // An exactly opposed damaged state has no separating forward normal.
                        // Slide laterally apart rather than cancelling either animal's travel.
                        val push = sqrt(depth * (radius(a) + radius(b))) * .5f + .01f
                        a.x -= sin(a.heading) * push; a.y += cos(a.heading) * push
                        b.x -= sin(b.heading) * push; b.y += cos(b.heading) * push
                    }
                }
            }
            if (!overlap) return
        }
        fish.forEachIndexed { i, f ->
            if (fish.take(i).any { bodyClearance(f, it) < 0f })
                place(f, fish.filter { it !== f }, Random(f.id xor time.toBits()), offscreen = true)
        }
    }

    private fun recycleOffscreen(f: ShoalFish) {
        if (f.x < -worldMargin || f.x > width + worldMargin || f.y < -worldMargin || f.y > height + worldMargin) {
            // Choose a clear offscreen location; wrapping into another fish can push a chain on screen.
            place(f, fish.filter { it !== f }, Random(f.id xor time.toBits()), offscreen = true)
        }
    }

    fun resize(w: Float, h: Float) {
        if (!w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f) return
        fish.forEach { it.x = it.x / width * w; it.y = it.y / height * h }
        foodX = foodX / width * w; foodY = foodY / height * h
        food.forEach { it.x = it.x / width * w; it.y = it.y / height * h }
        left = left / width * w; right = right / width * w; top = top / height * h; bottom = bottom / height * h
        width = w; height = h
        fish.forEach(::recycleOffscreen)
        separateFish()
    }
    // Versioned transient UI state; old six-fish formations are intentionally not restored.
    fun snapshot(): FloatArray = floatArrayOf(width, height, time, mode.ordinal.toFloat(), modeAge,
        if (typing) 1f else 0f, foodX, foodY, foodAge, left, top, right, bottom,
        foodResponders[0].toFloat(), foodResponders[1].toFloat(), 6f) +
        fish.flatMap { listOf(it.x, it.y, it.vx, it.vy, it.heading, it.turnRate, it.length, it.phase) }.toFloatArray() +
        floatArrayOf(food.size.toFloat()) + food.flatMap { listOf(it.x, it.y, it.age, if (it.consumed) 1f else 0f, it.fishId.toFloat()) }.toFloatArray()
    fun restore(data: FloatArray) {
        if (data.size < 16 || data.any { !it.isFinite() }) return
        val stride = when (data[15]) { 4f -> 9; 5f, 6f -> 8; else -> return }
        val foodStart = 16 + fishCount * stride
        val count = if (data[15] == 6f && data.size > foodStart) data[foodStart].toInt() else 0
        val expected = foodStart + if (data[15] == 6f) 1 + count * 5 else 0
        if (count !in 0..8 || data.size != expected || data[0] <= 0 || data[1] <= 0) return
        width = data[0]; height = data[1]; time = data[2]; mode = ShoalMode.entries[data[3].toInt().coerceIn(0, 3)]
        modeAge = data[4]; typing = data[5] > 0; foodX = data[6]; foodY = data[7]; foodAge = data[8]
        left = data[9]; top = data[10]; right = data[11]; bottom = data[12]
        foodResponders[0] = data[13].toInt(); foodResponders[1] = data[14].toInt()
        fish.forEachIndexed { i, f -> val start = 16 + i * stride
            f.x = data[start]; f.y = data[start + 1]; f.vx = data[start + 2]; f.vy = data[start + 3]
            f.heading = data[start + 4]; f.turnRate = data[start + 5].coerceIn(-.55f, .55f)
            f.length = data[start + 6].coerceIn(96f, 132f); f.phase = data[start + 7]
        }
        food.clear()
        if (data[15] == 6f) repeat(count) { i ->
            val at = foodStart + 1 + i * 5
            food += ShoalFood(data[at].coerceIn(0f, width), data[at + 1].coerceIn(0f, height),
                data[at + 2].coerceAtLeast(0f), data[at + 3] > .5f, data[at + 4].toInt().coerceIn(-1, fishCount - 1))
        } else if (foodAge < 2.4f) food += ShoalFood(foodX, foodY, foodAge, fishId = foodResponders[0])
        separateFish()
    }
    fun avoid(l: Float, t: Float, r: Float, b: Float) { left = l; top = t; right = r; bottom = b }
    fun input(hasText: Boolean) {
        typing = hasText
        if (mode == ShoalMode.IDLE || mode == ShoalMode.TYPING) setMode(if (typing) ShoalMode.TYPING else ShoalMode.IDLE)
    }
    fun feed(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        foodX = x.coerceIn(0f, width); foodY = y.coerceIn(0f, height); foodAge = 0f
        val cell = 7.5f * sizeScale
        val px = ((floor(foodX / cell) + .5f) * cell).coerceIn(0f, width)
        val py = ((floor(foodY / cell) + .5f) * cell).coerceIn(0f, height)
        if (food.size == 8) food.removeAt(0)
        val id = nearestFeeder(px, py)
        food += ShoalFood(px, py, fishId = id)
        foodResponders[0] = id; foodResponders[1] = -1
    }
    fun isFeeding(f: ShoalFish) = food.any { !it.consumed && it.age < 16f && it.fishId == f.id }
    private fun consumeFood() {
        food.forEach { pellet -> if (!pellet.consumed && fish.any {
            hypot(headX(it) - pellet.x, headY(it) - pellet.y) < 8f * sizeScale
        }) { pellet.consumed = true; pellet.age = 0f } }
    }
    fun submit(valid: Boolean): Boolean {
        if (!valid) return false
        typing = false; setMode(ShoalMode.BURST)
        // Submission changes UI state only; every species keeps its cruising pace.
        return true
    }
    private fun setMode(value: ShoalMode) { if (mode != value) { mode = value; modeAge = 0f } }
    private fun angleDelta(target: Float, current: Float) = atan2(sin(target - current), cos(target - current))
    fun update(elapsed: Float) {
        if (!elapsed.isFinite() || elapsed <= 0f) return
        val dt = elapsed.coerceAtMost(.05f)
        time += dt; modeAge += dt; foodAge += dt
        food.forEach { pellet ->
            pellet.age += dt
            if (!pellet.consumed && fish.none { it.id == pellet.fishId && isVisible(it) })
                pellet.fishId = nearestFeeder(pellet.x, pellet.y)
        }
        food.removeAll { it.age >= if (it.consumed) .7f else 16f }
        consumeFood()
        if (mode == ShoalMode.BURST && modeAge >= .55f) setMode(ShoalMode.RECOVERY)
        if (mode == ShoalMode.RECOVERY && modeAge >= 2.4f) setMode(if (typing) ShoalMode.TYPING else ShoalMode.IDLE)
        prepareBodies()
        fish.forEachIndexed { i, f ->
            val wander = sin(time * .19f + f.phase) * .19f + sin(time * .073f + f.phase * 2.2f) * .13f
            var sx = cos(f.heading + wander * 1.6f); var sy = sin(f.heading + wander * 1.6f)
            fish.forEach { other -> if (f !== other) {
                val dx = f.x - other.x; val dy = f.y - other.y
                val d = hypot(dx, dy).coerceAtLeast(.001f)
                val rvx = f.vx - other.vx; val rvy = f.vy - other.vy
                val closest = (-(dx * rvx + dy * rvy) / (rvx * rvx + rvy * rvy).coerceAtLeast(1f)).coerceIn(0f, 6f)
                val gap = minOf(bodyClearance(f, other), bodyClearance(f, other, closest))
                val reach = 180f * sizeScale
                if (gap < reach) {
                    val strength = ((reach - gap) / reach).coerceIn(0f, 2f)
                    sx += dx / d * strength - dy / d * strength * .45f
                    sy += dy / d * strength + dx / d * strength * .45f
                }
            } }
            val target = food.filter { !it.consumed && it.fishId == f.id }
                .minByOrNull { hypot(headX(f) - it.x, headY(f) - it.y) }
            if (target != null) {
                val dx = target.x - headX(f); val dy = target.y - headY(f)
                val d = hypot(dx, dy).coerceAtLeast(1f)
                sx += dx / d * 2.5f; sy += dy / d * 2.5f
            }
            if (right > left && bottom > top && f.x > left - 32 && f.x < right + 32 && f.y > top - 32 && f.y < bottom + 32) {
                val nearX = minOf(f.x - left + 32, right + 32 - f.x)
                val nearY = minOf(f.y - top + 32, bottom + 32 - f.y)
                if (nearX < nearY) sx += if (f.x < (left + right) / 2) -1.2f else 1.2f
                else sy += if (f.y < (top + bottom) / 2) -1.2f else 1.2f
            }
            // No alignment, cohesion, typing attraction or forces at the phone's edges.
            val maxTurn = minOf(.55f, hypot(f.vx, f.vy) / (visibleLength(f) * 1.6f))
            val targetRate = (angleDelta(atan2(sy, sx), f.heading) * .8f).coerceIn(-maxTurn, maxTurn)
            f.turnRate = (f.turnRate + (targetRate - f.turnRate).coerceIn(-.7f * dt, .7f * dt)).coerceIn(-maxTurn, maxTurn)
            headings[i] = f.heading + f.turnRate * dt
        }
        fish.forEachIndexed { i, f ->
            val speed = cruiseSpeed(f)
            f.heading = headings[i]; f.vx = cos(f.heading) * speed; f.vy = sin(f.heading) * speed
            f.x += f.vx * dt; f.y += f.vy * dt
            // Recycle only beyond the offscreen world: never bounce or teleport at a screen edge.
            recycleOffscreen(f)
        }
        separateFish()
        consumeFood()
    }
}
