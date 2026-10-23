package no.hvl.cpn.sim

import no.hvl.cpn.core.ColourSets
import no.hvl.cpn.core.Multiset
import no.hvl.cpn.core.Net
import no.hvl.cpn.core.Place
import no.hvl.cpn.core.Transition
import no.hvl.cpn.core.Variable
import no.hvl.cpn.core.net
import no.hvl.cpn.core.toMultiset

/**
 * The simple protocol of Jensen & Kristensen (2009), ch. 2 (Fig. 2.10), with inscriptions as Kotlin code:
 *
 * ```
 * colset NO = int; colset DATA = string; colset NOxDATA = product NO * DATA; colset BOOL = bool;
 * var n, k : NO; var d, data : DATA; var success : BOOL;
 * ```
 */
class SimpleProtocol {
    val no = ColourSets.INT
    val dataCs = ColourSets.STRING
    val noXData = ColourSets.product("NOxDATA", no, dataCs)

    lateinit var n: Variable<Int>
    lateinit var k: Variable<Int>
    lateinit var d: Variable<String>
    lateinit var data: Variable<String>
    lateinit var success: Variable<Boolean>

    lateinit var packetsToSend: Place<Pair<Int, String>>
    lateinit var a: Place<Pair<Int, String>>
    lateinit var b: Place<Pair<Int, String>>
    lateinit var c: Place<Int>
    lateinit var dPlace: Place<Int>
    lateinit var nextSend: Place<Int>
    lateinit var nextRec: Place<Int>
    lateinit var dataReceived: Place<String>

    lateinit var sendPacket: Transition
    lateinit var transmitPacket: Transition
    lateinit var receivePacket: Transition
    lateinit var transmitAck: Transition
    lateinit var receiveAck: Transition

    val packets = listOf(1 to "COL", 2 to "OUR", 3 to "ED ", 4 to "PET", 5 to "RI ", 6 to "NET")

    val net: Net =
        net("SimpleProtocol") {
            n = variable("n", no)
            k = variable("k", no)
            d = variable("d", dataCs)
            data = variable("data", dataCs)
            success = variable("success", ColourSets.BOOL)
            page("Protocol") {
                packetsToSend = place("PacketsToSend", noXData, packets.toMultiset())
                a = place("A", noXData)
                b = place("B", noXData)
                c = place("C", no)
                dPlace = place("D", no)
                nextSend = place("NextSend", no, Multiset.of(1))
                nextRec = place("NextRec", no, Multiset.of(1))
                dataReceived = place("DataReceived", dataCs, Multiset.of(""))

                sendPacket =
                    transition("SendPacket") {
                        both(packetsToSend, tuple(n, d))
                        both(nextSend, n)
                        output(a, tuple(n, d))
                    }
                transmitPacket =
                    transition("TransmitPacket") {
                        input(a, tuple(n, d))
                        output(b, n, d, success) { if (it[success]) pair(it[n], it[d]) else Multiset.empty() }
                    }
                receivePacket =
                    transition("ReceivePacket") {
                        input(b, tuple(n, d))
                        input(nextRec, k)
                        output(nextRec, n, k) { Multiset.of(if (it[n] == it[k]) it[k] + 1 else it[k]) }
                        input(dataReceived, data)
                        output(dataReceived, n, k, d, data) { Multiset.of(if (it[n] == it[k]) it[data] + it[d] else it[data]) }
                        output(c, n, k) { Multiset.of(if (it[n] == it[k]) it[k] + 1 else it[k]) }
                    }
                transmitAck =
                    transition("TransmitAck") {
                        input(c, n)
                        output(dPlace, n, success) { if (it[success]) Multiset.of(it[n]) else Multiset.empty() }
                    }
                receiveAck =
                    transition("ReceiveAck") {
                        input(dPlace, n)
                        input(nextSend, k)
                        output(nextSend, n)
                    }
            }
        }

    private fun pair(
        n: Int,
        d: String,
    ): Multiset<Pair<Int, String>> = Multiset.of(n to d)
}
