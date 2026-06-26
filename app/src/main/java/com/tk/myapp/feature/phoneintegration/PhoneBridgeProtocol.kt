package com.tk.myapp.feature.phoneintegration

object PhoneBridgeProtocol {
    const val version = 1
    const val serviceType = "_tk-toolkit-phone._tcp."
    const val serviceNamePrefix = "Toolkit Android"

    const val typePairHello = "pair.hello"
    const val typePairChallenge = "pair.challenge"
    const val typePairDecision = "pair.decision"
    const val typePairComplete = "pair.complete"
    const val typeError = "error"

    const val typeListFiles = "files.list"
    const val typeListFilesResult = "files.list.result"
    const val typeReadFile = "file.read"
    const val typeReadFileResult = "file.read.result"
    const val typeShareFileChunk = "file.share.chunk"
    const val typeShareFileResult = "file.share.result"
}
