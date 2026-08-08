package com.farmmathbuilder.app.domain

import kotlin.random.Random

data class Exercise(
    val operandA: Int,
    val operandB: Int,
    val correctAnswer: Int,
    val choices: List<Int>,
    val operation: MathOperation
)

/**
 * Exercises scaled by age band (FR-063): addition + subtraction for all ages,
 * plus single-digit multiplication/division for the older band (`AgeBand.operations`).
 * 4 multiple choice answers (one correct + 3 distractors, no duplicates).
 */
object MathExerciseGenerator {

    fun generate(ageBand: AgeBand, random: Random = Random.Default): Exercise {
        val operation = ageBand.operations.random(random)

        val operandA: Int
        val operandB: Int
        val correct: Int

        when (operation) {
            MathOperation.ADD -> {
                operandA = random.nextInt(ageBand.minOperand, ageBand.maxOperand + 1)
                operandB = random.nextInt(ageBand.minOperand, ageBand.maxOperand + 1)
                correct = operandA + operandB
            }
            MathOperation.SUBTRACT -> {
                val x = random.nextInt(ageBand.minOperand, ageBand.maxOperand + 1)
                val y = random.nextInt(ageBand.minOperand, ageBand.maxOperand + 1)
                operandA = maxOf(x, y)
                operandB = minOf(x, y)
                correct = operandA - operandB
            }
            MathOperation.MULTIPLY -> {
                operandA = random.nextInt(1, 10)
                operandB = random.nextInt(1, 10)
                correct = operandA * operandB
            }
            MathOperation.DIVIDE -> {
                val divisor = random.nextInt(1, 10)
                val quotient = random.nextInt(1, 10)
                operandA = divisor * quotient
                operandB = divisor
                correct = quotient
            }
        }

        val choices = mutableSetOf(correct)
        val delta = when (operation) {
            MathOperation.MULTIPLY, MathOperation.DIVIDE -> 3
            MathOperation.ADD, MathOperation.SUBTRACT -> 5
        }
        val maxPossible = when (operation) {
            MathOperation.ADD -> ageBand.maxOperand * 2
            MathOperation.SUBTRACT -> ageBand.maxOperand
            MathOperation.MULTIPLY -> 9 * 9
            MathOperation.DIVIDE -> 9
        }
        var guardCounter = 0
        while (choices.size < 4 && guardCounter < 200) {
            guardCounter++
            val candidateDelta = random.nextInt(-delta, delta + 1)
            var candidate = correct + candidateDelta
            if (candidate == correct || candidate < 0) {
                candidate = random.nextInt(0, maxPossible + 1)
            }
            choices.add(candidate)
        }
        // Fallback in the unlikely event we still don't have 4 unique values.
        var filler = 0
        while (choices.size < 4) {
            if (!choices.contains(filler)) choices.add(filler)
            filler++
        }

        return Exercise(
            operandA = operandA,
            operandB = operandB,
            correctAnswer = correct,
            choices = choices.toList().shuffled(random),
            operation = operation
        )
    }
}
