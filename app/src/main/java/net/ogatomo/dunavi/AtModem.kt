package net.ogatomo.dunavi

data class ModemResult(
    val output: ByteArray = byteArrayOf(),
    val dial: Boolean = false,
    val commandComplete: Boolean = false,
)

/** Hayes/V.250 command subset needed by Bluetooth DUN data terminals. */
class AtModem(
    private val detailed: Boolean = false,
    private val log: (direction: String, message: String) -> Unit = { _, _ -> },
) {
    private val line = StringBuilder()
    private var echo = true
    private var quiet = false
    private var verbose = true
    private var commandTerminator = 13
    private var responseTerminator = 13
    private var responseSeparator = 10
    private var backspace = 8

    fun accept(value: Int): ModemResult {
        val byte = value and 0xff
        if (byte == responseSeparator) return ModemResult()
        if (byte == backspace || byte == 127) {
            if (line.isNotEmpty()) line.deleteAt(line.lastIndex)
            return ModemResult(if (echo) byteArrayOf(backspace.toByte(), 32, backspace.toByte()) else byteArrayOf())
        }
        if (byte != commandTerminator) {
            if (line.length < 256) line.append(byte.toChar())
            return ModemResult(if (echo) byteArrayOf(byte.toByte()) else byteArrayOf())
        }

        val command = line.toString().trim().uppercase()
        line.clear()
        log("RX", display(command))
        val response = execute(command)
        log("TX", listOfNotNull(response.info, response.code.label).joinToString(" "))
        return ModemResult(format(response), response.dial, commandComplete = true)
    }

    private fun execute(command: String): Response {
        if (!command.startsWith("AT")) return Response(ERROR)
        val body = command.substring(2)
        if (body.isEmpty()) return Response(OK)
        if (body.startsWith("@")) return vendorCommand(body)
        if (body.startsWith("D")) return dialCommand(body.substring(1))
        if (CGDATA.matches(command)) return Response(CONNECT, dial = true)
        return when {
            body == "+GCAP" -> Response(OK, "+GCAP: +CGSM")
            body == "+GMI" -> Response(OK, "OgaTomo Systems")
            body == "+GMM" -> Response(OK, "DUNavi")
            body == "+GMR" -> Response(OK, "1.0.0")
            body.startsWith("+CGDCONT=") || body == "+CGDCONT?" -> Response(OK)
            body == "+CGATT=0" || body == "+CGATT=1" -> Response(OK)
            body == "+CGATT?" -> Response(OK, "+CGATT: 1")
            body == "+CGREG?" -> Response(OK, "+CGREG: 0,1")
            body == "+CREG?" -> Response(OK, "+CREG: 0,1")
            body.matches(Regex("\\+(CGREG|CREG)=[0-2]")) -> Response(OK)
            body == "+CSQ" || body == "+CSQ?" -> Response(OK, "+CSQ: 20,99")
            body == "+CSQ=?" -> Response(OK, "+CSQ: (0-31,99),(0-7,99)")
            COMPAT_NO_OP.any(body::startsWith) -> Response(OK)
            body.startsWith("+") -> Response(ERROR)
            else -> executeBasic(body)
        }
    }

    /**
     * Vendor-specific commands. AT@AUBTSET is emitted by some Toyota/legacy handsets and car
     * navigation units during Bluetooth setup; no public specification exists, so answer OK
     * explicitly instead of ERROR (an ERROR there makes some callers abort the session).
     */
    private fun vendorCommand(body: String): Response {
        if (body == "@AUBTSET" || body == "@AUBTSET?" || AUBTSET_ASSIGN.matches(body)) return Response(OK)
        return Response(ERROR)
    }

    /**
     * Bluetooth DUN dialling. Standard packet-data forms (the *99# and *98# families) are data
     * calls, and this RFCOMM DUN endpoint also accepts plain numbers from legacy callers (e.g.
     * car navigation units) as data calls. A trailing ';' marks a voice call request: acknowledge
     * OK and stay in command mode; an embedded ';' is malformed and must not start PPP.
     */
    private fun dialCommand(body: String): Response {
        val rest = when (body.firstOrNull()) { 'T', 'P' -> body.substring(1); else -> body }
        if (rest.isEmpty() || rest.any { it !in DIAL_CHARACTERS }) return Response(ERROR)
        val voiceMarker = rest.indexOf(';')
        if (voiceMarker >= 0 && voiceMarker != rest.lastIndex) return Response(ERROR)
        if (rest.none { it.isDigit() || it == '*' || it == '#' }) return Response(ERROR)
        if (voiceMarker >= 0) return Response(OK)
        return Response(CONNECT, dial = true)
    }

    private fun executeBasic(body: String): Response {
        var offset = 0
        var info: String? = null
        while (offset < body.length) {
            val match = BASIC.find(body, offset)?.takeIf { it.range.first == offset } ?: return Response(ERROR)
            val token = match.value
            when {
                token.startsWith("&F") || token.startsWith("Z") -> reset()
                token.startsWith("E") -> echo = token.lastOrNull()?.digitToIntOrNull() != 0
                token.startsWith("Q") -> quiet = token.lastOrNull()?.digitToIntOrNull() == 1
                token.startsWith("V") -> verbose = token.lastOrNull()?.digitToIntOrNull() != 0
                token.startsWith("S") -> info = handleRegister(token) ?: info
                token.startsWith("I") -> info = "DUNavi 1.0"
            }
            offset = match.range.last + 1
        }
        return Response(OK, info)
    }

    private fun handleRegister(token: String): String? {
        val number = token.drop(1).takeWhile(Char::isDigit).toIntOrNull() ?: return null
        if (number !in REGISTERS) return null
        if (token.endsWith("?")) return register(number).toString()
        val value = token.substringAfter('=', "").toIntOrNull()
        if (value != null) when (number) {
            3 -> {
                commandTerminator = value and 0xff
                responseTerminator = value and 0xff
            }
            4 -> responseSeparator = value and 0xff
            5 -> backspace = value and 0xff
        }
        return null
    }

    private fun register(number: Int) = when (number) {
        0 -> 0
        3 -> commandTerminator
        4 -> responseSeparator
        5 -> backspace
        6 -> 2
        7 -> 50
        8 -> 2
        10 -> 14
        else -> 0
    }

    private fun reset() {
        echo = true
        quiet = false
        verbose = true
        commandTerminator = 13
        responseTerminator = 13
        responseSeparator = 10
        backspace = 8
    }

    private fun format(response: Response): ByteArray {
        if (quiet) return byteArrayOf()
        val parts = buildList {
            response.info?.let(::add)
            add(if (verbose) response.code.label else response.code.numeric.toString())
        }
        val prefix = "${responseTerminator.toChar()}${responseSeparator.toChar()}"
        return (prefix + parts.joinToString(prefix) + prefix).toByteArray(Charsets.US_ASCII)
    }

    /**
     * Log-safe rendering of a received command. In detailed mode the full structure is kept;
     * otherwise dial digits and assigned values are masked while the shape stays readable.
     */
    private fun display(command: String): String {
        if (detailed) return command.take(256)
        if (command.startsWith("ATD")) return maskedDial(command)
        if (command.startsWith("AT@")) {
            if (command.startsWith("AT@AUBTSET")) return command.take(96)
            return command.substringBefore('=').take(32) + if ('=' in command) "=<redacted>" else ""
        }
        if (!command.startsWith("AT+")) return command.take(96)
        val name = command.substringBefore('=').substringBefore('?').take(32)
        return if ('=' in command && !command.endsWith("=?")) "$name=<redacted>" else command.take(96)
    }

    /** Keeps dial-string structure (prefix, *, #, separators, length) but hides the digits. */
    private fun maskedDial(command: String): String {
        val body = command.substring(3)
        val rest = when (body.firstOrNull()) { 'T', 'P' -> body.substring(1); else -> body }
        if (PACKET_DIAL.matches(rest)) return command.take(96) // standard packet codes carry no secrets
        return "ATD" + body.map { if (it.isDigit()) 'd' else it }.joinToString("")
    }

    private data class Response(val code: ResultCode, val info: String? = null, val dial: Boolean = false)
    private data class ResultCode(val label: String, val numeric: Int)

    private companion object {
        val OK = ResultCode("OK", 0)
        val CONNECT = ResultCode("CONNECT", 1)
        val ERROR = ResultCode("ERROR", 4)
        val PACKET_DIAL = Regex("^\\*?(?:99(?:\\*{3}[0-9]+)?|98(?:\\*[0-9]+)?)#$")
        val AUBTSET_ASSIGN = Regex("^@AUBTSET=[0-9]+$")
        val CGDATA = Regex("AT\\+CGDATA=\\\"PPP\\\",[0-9]+")
        val DIAL_CHARACTERS = "0123456789*#,;!WABCD+()- ".toSet()
        val BASIC = Regex("&[CDF](?:[0-9])?|E[01]?|H[01]?|I[0-9]?|L[0-3]?|M[0-2]?|O[01]?|P|Q[01]?|S(?:0|3|4|5|6|7|8|10)(?:\\?|=[0-9]+)?|T|V[01]?|X[0-4]?|Z[01]?")
        val REGISTERS = setOf(0, 3, 4, 5, 6, 7, 8, 10)
        // Side-effect-free compatibility settings observed in, or adjacent to, legacy DUN setup.
        // Unknown commands outside this explicit list still return ERROR.
        val COMPAT_NO_OP = listOf(
            "+CGQREQ=", "+CGQMIN=", "+CGEQREQ=", "+CGEQMIN=",
            "+CGEREP=", "+CGAUTO=", "+CGACT=", "+CGCLASS=",
        )
    }
}
