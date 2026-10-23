package dev.kotlinds.libretrorollback.internal

internal object ProtocolMessages {
    fun hello(sessionId: Int, player: Int, players: Int, inputSize: Int, hello: Hello): ByteArray {
        val payload = ByteWriter(128).apply {
            str(hello.coreName)
            str(hello.coreVersion)
            str(hello.contentSha256)
            u64(hello.optionsHash)
            u32(hello.stateSize)
            u64(hello.initialChecksum)
            u8(hello.inputDelay)
            u8(hello.maxPredictionFrames)
            u8(if (hello.linkEnabled) 1 else 0)
            u8(hello.linkDelay)
        }.toByteArray()
        return Protocol.encode(Packet(PacketType.HELLO, sessionId, player, players, inputSize, 0, -1, 0, payload))
    }

    fun simple(
        type: Int,
        sessionId: Int,
        player: Int,
        players: Int,
        inputSize: Int,
        frame: Int = 0,
        ackFrame: Int = -1,
        checksum: Int = 0,
        payload: ByteArray = ByteArray(0),
    ): ByteArray = Protocol.encode(Packet(type, sessionId, player, players, inputSize, frame, ackFrame, checksum, payload))

    fun input(
        sessionId: Int,
        player: Int,
        players: Int,
        inputSize: Int,
        frame: Int,
        ackFrame: Int,
        checksum: Int,
        startFrame: Int,
        inputs: ByteArray,
        count: Int,
    ): ByteArray {
        val payload = ByteWriter(8 + inputs.size).apply {
            u32(startFrame)
            u8(count)
            bytes(inputs)
        }.toByteArray()
        return Protocol.encode(Packet(PacketType.INPUT, sessionId, player, players, inputSize, frame, ackFrame, checksum, payload))
    }

    /**
     * Local wireless batches. [batches] is the recent history (oldest first) so a lost packet is
     * recovered by the next one, exactly like redundant inputs. [epoch] plus [invalidatedFrom] tell
     * the receiver that the batches replace everything it still has scheduled from an earlier epoch
     * for frames from [invalidatedFrom] on.
     */
    fun link(
        sessionId: Int,
        player: Int,
        players: Int,
        inputSize: Int,
        frame: Int,
        ackFrame: Int,
        checksum: Int,
        epoch: Int,
        invalidatedFrom: Int,
        batches: List<LinkBatch>,
    ): ByteArray {
        val size = 13 + batches.sumOf { 5 + it.packets.sumOf { packet -> 2 + packet.size } }
        val payload = ByteWriter(size).apply {
            u8(epoch)
            u32(invalidatedFrom)
            u32(batches.firstOrNull()?.frame ?: 0)
            u8(batches.size)
            for (batch in batches) {
                u32(batch.frame)
                u8(batch.packets.size)
                for (packet in batch.packets) {
                    u16(packet.size)
                    bytes(packet)
                }
            }
        }.toByteArray()
        return Protocol.encode(Packet(PacketType.LINK, sessionId, player, players, inputSize, frame, ackFrame, checksum, payload))
    }

    fun decodeLink(payload: ByteArray): LinkPayload? = runCatching {
        val reader = ByteReader(payload, payload.size)
        val epoch = reader.u8()
        val invalidatedFrom = reader.u32()
        reader.u32()
        val batchCount = reader.u8()
        val batches = ArrayList<LinkBatch>(batchCount)
        repeat(batchCount) {
            val frame = reader.u32()
            val packetCount = reader.u8()
            val packets = ArrayList<ByteArray>(packetCount)
            repeat(packetCount) {
                val length = reader.u16()
                if (length <= 0 || reader.remaining < length) return null
                packets.add(reader.bytes(length))
            }
            batches.add(LinkBatch(frame, packets))
        }
        LinkPayload(epoch, invalidatedFrom, batches)
    }.getOrNull()

    fun desync(
        sessionId: Int,
        player: Int,
        players: Int,
        inputSize: Int,
        frame: Int,
        localChecksum: Long,
        remoteChecksum: Long,
    ): ByteArray {
        val payload = ByteWriter(20).apply {
            u32(frame)
            u64(localChecksum)
            u64(remoteChecksum)
        }.toByteArray()
        return Protocol.encode(Packet(PacketType.DESYNC, sessionId, player, players, inputSize, frame, -1, 0, payload))
    }

    fun bye(sessionId: Int, player: Int, players: Int, inputSize: Int, reason: String): ByteArray {
        val payload = ByteWriter(32).apply { str(reason) }.toByteArray()
        return Protocol.encode(Packet(PacketType.BYE, sessionId, player, players, inputSize, 0, -1, 0, payload))
    }

    fun decodeHello(payload: ByteArray): Hello? = runCatching {
        val reader = ByteReader(payload, payload.size)
        Hello(
            coreName = reader.str(),
            coreVersion = reader.str(),
            contentSha256 = reader.str(),
            optionsHash = reader.u64(),
            stateSize = reader.u32(),
            initialChecksum = reader.u64(),
            inputDelay = reader.u8(),
            maxPredictionFrames = reader.u8(),
            // Optional trailing fields: a peer without linked play may not send them.
            linkEnabled = if (reader.remaining >= 2) reader.u8() != 0 else false,
            linkDelay = if (reader.remaining >= 1) reader.u8() else 0,
        )
    }.getOrNull()

    fun decodeInput(payload: ByteArray, inputSize: Int): InputPayload? = runCatching {
        val reader = ByteReader(payload, payload.size)
        val startFrame = reader.u32()
        val count = reader.u8()
        if (count <= 0 || reader.remaining < count * inputSize) return null
        InputPayload(startFrame, count, reader.bytes(count * inputSize))
    }.getOrNull()

    fun decodeDesync(payload: ByteArray): Triple<Int, Long, Long>? = runCatching {
        val reader = ByteReader(payload, payload.size)
        Triple(reader.u32(), reader.u64(), reader.u64())
    }.getOrNull()

    fun decodeReason(payload: ByteArray): String? = runCatching {
        ByteReader(payload, payload.size).str()
    }.getOrNull()
}
