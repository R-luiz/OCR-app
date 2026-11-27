package com.ocrapp.ocr

/**
 * An [OcrConnectionProvider] that answers from a list, one lookup at a time, and records
 * what the engine asked and what it reported back.
 *
 * Past the end of the list it keeps repeating the last answer, so a test can make a
 * second lookup differ from the first and show that a scan never makes one.
 */
class FakeConnections(private val answers: List<ConnectionLookup>) : OcrConnectionProvider {

    constructor(connection: OcrConnection) : this(listOf(ConnectionLookup.Available(connection)))

    /** How many times the engine looked the connection up. */
    var lookups = 0
        private set

    /** The sessions the engine reported as refused, in order. */
    val rejected = mutableListOf<OcrConnection.Managed>()

    override suspend fun lookup(): ConnectionLookup = answers[minOf(lookups++, answers.lastIndex)]

    override suspend fun sessionRejected(connection: OcrConnection.Managed) {
        rejected += connection
    }
}
