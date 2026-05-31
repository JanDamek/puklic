// Bridge.swift — Apple CryptoKit ChaChaPoly AEAD bridge for Kotlin/Native cinterop.
//
// Exposes two C symbols (puklic_chachapoly_seal, puklic_chachapoly_open) implementing
// RFC 7539 ChaCha20-Poly1305 backed by CryptoKit.ChaChaPoly. The XChaCha20 extension
// (24-byte nonce + HChaCha20 subkey derivation) is performed in pure Kotlin on the
// caller side — this bridge sees only the derived 32-byte subkey and the 12-byte inner
// IETF nonce, identical to how the JVM BouncyCastle actual treats the inner cipher.
//
// Output format on seal: ciphertext || tag (16-byte Poly1305 tag appended) — matches
// the JVM BouncyCastle output byte-for-byte and the libsodium
// `crypto_aead_chacha20poly1305_ietf_encrypt` convention.
//
// Built into a static library libpuklic_crypto.a per iOS slice by the Gradle
// `swiftCompilePuklicCryptoBridge*` tasks in shared/voice-codec/build.gradle.kts.
//
// See architect report
//   docs/03_infrastructure/architect-reports/2026-05-29-fp14h-2c-implementation.md §3.1.

import Foundation
import CryptoKit

private let TAG_BYTES: Int = 16

@_cdecl("puklic_chachapoly_seal")
public func puklic_chachapoly_seal(
    _ keyPtr: UnsafePointer<UInt8>, _ keyLen: Int,
    _ noncePtr: UnsafePointer<UInt8>, _ nonceLen: Int,
    _ aadPtr: UnsafePointer<UInt8>?, _ aadLen: Int,
    _ ptPtr: UnsafePointer<UInt8>?, _ ptLen: Int,
    _ outPtr: UnsafeMutablePointer<UInt8>,
    _ outLenPtr: UnsafeMutablePointer<Int>
) -> Int32 {
    let key = SymmetricKey(data: Data(bytes: keyPtr, count: keyLen))

    let nonce: ChaChaPoly.Nonce
    do {
        nonce = try ChaChaPoly.Nonce(data: Data(bytes: noncePtr, count: nonceLen))
    } catch {
        return -1
    }

    let aad: Data = (aadPtr != nil && aadLen > 0) ? Data(bytes: aadPtr!, count: aadLen) : Data()
    let plaintext: Data = (ptPtr != nil && ptLen > 0) ? Data(bytes: ptPtr!, count: ptLen) : Data()

    let sealed: ChaChaPoly.SealedBox
    do {
        sealed = try ChaChaPoly.seal(plaintext, using: key, nonce: nonce, authenticating: aad)
    } catch {
        return -2
    }

    var combined = Data()
    combined.reserveCapacity(sealed.ciphertext.count + sealed.tag.count)
    combined.append(sealed.ciphertext)
    combined.append(sealed.tag)

    combined.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
        if let base = raw.bindMemory(to: UInt8.self).baseAddress {
            outPtr.update(from: base, count: combined.count)
        }
    }
    outLenPtr.pointee = combined.count
    return 0
}

@_cdecl("puklic_chachapoly_open")
public func puklic_chachapoly_open(
    _ keyPtr: UnsafePointer<UInt8>, _ keyLen: Int,
    _ noncePtr: UnsafePointer<UInt8>, _ nonceLen: Int,
    _ aadPtr: UnsafePointer<UInt8>?, _ aadLen: Int,
    _ ctTagPtr: UnsafePointer<UInt8>, _ ctTagLen: Int,
    _ outPtr: UnsafeMutablePointer<UInt8>,
    _ outLenPtr: UnsafeMutablePointer<Int>
) -> Int32 {
    guard ctTagLen >= TAG_BYTES else { return -4 }

    let key = SymmetricKey(data: Data(bytes: keyPtr, count: keyLen))

    let nonce: ChaChaPoly.Nonce
    do {
        nonce = try ChaChaPoly.Nonce(data: Data(bytes: noncePtr, count: nonceLen))
    } catch {
        return -1
    }

    let aad: Data = (aadPtr != nil && aadLen > 0) ? Data(bytes: aadPtr!, count: aadLen) : Data()

    let ctLen = ctTagLen - TAG_BYTES
    let ciphertext = Data(bytes: ctTagPtr, count: ctLen)
    let tag = Data(bytes: ctTagPtr.advanced(by: ctLen), count: TAG_BYTES)

    let sealed: ChaChaPoly.SealedBox
    do {
        sealed = try ChaChaPoly.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
    } catch {
        return -2
    }

    let plaintext: Data
    do {
        plaintext = try ChaChaPoly.open(sealed, using: key, authenticating: aad)
    } catch {
        return -3
    }

    if plaintext.count > 0 {
        plaintext.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            if let base = raw.bindMemory(to: UInt8.self).baseAddress {
                outPtr.update(from: base, count: plaintext.count)
            }
        }
    }
    outLenPtr.pointee = plaintext.count
    return 0
}
