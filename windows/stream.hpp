#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <optional>

namespace mwf {
constexpr unsigned sampleRate = 48000;
constexpr unsigned frames = 480;
constexpr unsigned frameBytes = 2;
constexpr unsigned audioBytes = frames * frameBytes;
constexpr unsigned packetBytes = 8 + audioBytes;
constexpr unsigned short port = 47831;
constexpr unsigned short videoPort = 47832;
constexpr std::uint64_t idleMs = 300;
using Pcm = std::array<std::uint8_t, audioBytes>;

struct Packet {
    std::uint32_t seq;
    Pcm pcm;
};

inline bool decode(const char* bytes, std::size_t len, Packet& packet) {
    if (len != packetBytes || std::memcmp(bytes, "MWF1", 4) != 0) return false;
    const auto* p = reinterpret_cast<const unsigned char*>(bytes + 4);
    packet.seq = (std::uint32_t{p[0]} << 24) | (std::uint32_t{p[1]} << 16) |
                 (std::uint32_t{p[2]} << 8) | std::uint32_t{p[3]};
    std::memcpy(packet.pcm.data(), p + 4, audioBytes);
    return true;
}

struct Peer {
    std::uint32_t ip;
    std::uint16_t port;
    bool operator==(const Peer& other) const { return ip == other.ip && port == other.port; }
};

struct RecentPeer {
    Peer peer;
    std::uint64_t receivedAt;

    bool active(std::uint64_t now) const { return now - receivedAt < idleMs; }
};

// La dirección llega convertida a orden de host. Solo IPv4 unicast no local al bucle.
inline bool usableVideoIpv4(std::uint32_t ip) {
    const auto firstOctet = ip >> 24;
    return firstOctet != 0 && firstOctet != 127 && firstOctet < 224;
}

// Desplazamientos en aritmética modular, incluido el salto UINT32_MAX -> 0.
class Stream {
    static constexpr unsigned window = 8;
    static constexpr std::uint32_t half = 0x80000000u;
    struct Slot {
        bool occupied = false;
        Packet packet{};
    };
    std::array<Slot, window> slots_{};
    Peer peer_{};
    std::uint32_t next_ = 0;
    std::uint64_t first_ = 0;
    std::uint64_t last_ = 0;
    unsigned count_ = 0;
    unsigned emptyRun_ = 0;
    bool havePeer_ = false;
    bool playing_ = false;
    bool starved_ = false;

    Slot& slot(std::uint32_t seq) { return slots_[seq % window]; }

    void clearSlots() {
        for (auto& s : slots_) s.occupied = false;
        count_ = 0;
    }

    void begin(Peer peer, std::uint32_t seq, std::uint64_t now) {
        clearSlots();
        next_ = seq;
        first_ = now;
        peer_ = peer;
        havePeer_ = true;
        playing_ = false;
        starved_ = false;
        emptyRun_ = 0;
    }

public:
    std::optional<RecentPeer> activePeer(std::uint64_t now) const {
        if (!havePeer_) return std::nullopt;
        const RecentPeer recent{peer_, last_};
        return recent.active(now) ? std::optional<RecentPeer>{recent} : std::nullopt;
    }

    bool expire(std::uint64_t now) {
        if (!havePeer_ || activePeer(now)) return false;
        clearSlots();
        havePeer_ = playing_ = false;
        return true;
    }

    void discardAudio() {
        clearSlots();
        starved_ = havePeer_;
    }

    bool push(Peer peer, const Packet& packet, std::uint64_t now) {
        if (!havePeer_) begin(peer, packet.seq, now);
        if (!(peer == peer_)) return false;
        // Tras agotar el búfer, ajustamos el reloj al próximo paquete no reproducido.
        if (starved_) {
            if (packet.seq - next_ >= half) return false;
            begin(peer, packet.seq, now);
        }
        const auto ahead = packet.seq - next_;
        if (ahead >= half) {
            const auto behind = next_ - packet.seq;
            if (playing_ || behind >= window) return false;
            // Admite un paquete anterior al primero antes de arrancar, sin agrandar la ventana.
            for (std::uint32_t i = window - behind; i < window; ++i) {
                auto& s = slot(next_ + i);
                if (s.occupied) { s.occupied = false; --count_; }
            }
            next_ = packet.seq;
        } else if (ahead >= window) {
            // No esperamos cortes grandes: saltamos al audio reciente y volvemos a acumular.
            begin(peer, packet.seq, now);
        }
        auto& s = slot(packet.seq);
        if (s.occupied) return false;
        s.packet = packet;
        s.occupied = true;
        ++count_;
        last_ = now;
        return true;
    }

    bool ready(std::uint64_t now) const {
        return havePeer_ && !starved_ && (playing_ || count_ >= 3 || now - first_ >= 40);
    }

    Pcm pop() {
        playing_ = true;
        auto& s = slot(next_++);
        if (!s.occupied) {
            if (count_ == 0 && ++emptyRun_ == 2) {
                starved_ = true;
                next_ -= emptyRun_;  // El silencio no cuenta como paquetes recibidos.
            }
            return {};
        }
        emptyRun_ = 0;
        s.occupied = false;
        --count_;
        return s.packet.pcm;
    }
};
} // namespace mwf
