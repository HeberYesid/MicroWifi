#include "stream.hpp"

#include <cstdio>
#include <cstdlib>

#define CHECK(expr) do { if (!(expr)) { std::fprintf(stderr, "Línea %d: %s\n", __LINE__, #expr); std::exit(1); } } while (false)

mwf::Packet packet(std::uint32_t seq, std::uint8_t marker) {
    mwf::Packet result{seq, {}};
    result.pcm[0] = marker;
    return result;
}

int main() {
    char bytes[mwf::packetBytes + 1] = "MWF1\x89\xab\xcd\xef\x34\x12";
    mwf::Packet decoded{};
    CHECK(mwf::decode(bytes, mwf::packetBytes, decoded));
    CHECK(decoded.seq == 0x89abcdef && decoded.pcm[0] == 0x34 && decoded.pcm[1] == 0x12);
    CHECK(!mwf::decode(bytes, mwf::packetBytes - 1, decoded));
    CHECK(!mwf::decode(bytes, mwf::packetBytes + 1, decoded));
    bytes[0] = 'X';
    CHECK(!mwf::decode(bytes, mwf::packetBytes, decoded));

    constexpr mwf::Peer a{1, 2}, b{1, 3};
    mwf::Stream stream;
    stream.push(a, packet(0xffffffffu, 2), 0);
    stream.push(a, packet(0xfffffffeu, 1), 1);
    stream.push(a, packet(0xffffffffu, 9), 2);
    stream.push(b, packet(0, 9), 2);
    CHECK(!stream.ready(2));
    stream.push(a, packet(0, 3), 3);
    CHECK(stream.ready(3));
    CHECK(stream.pop()[0] == 1);
    CHECK(stream.pop()[0] == 2);
    CHECK(stream.pop()[0] == 3);
    stream.push(a, packet(2, 5), 20);
    stream.push(a, packet(0, 9), 20);
    CHECK(stream.pop() == mwf::Pcm{});
    CHECK(stream.pop()[0] == 5);
    CHECK(stream.pop() == mwf::Pcm{});
    CHECK(stream.pop() == mwf::Pcm{});
    CHECK(!stream.ready(70));
    stream.push(a, packet(2, 9), 70);
    CHECK(!stream.ready(100));
    stream.push(a, packet(3, 6), 100);
    CHECK(!stream.ready(139));
    CHECK(stream.ready(140));
    CHECK(stream.pop()[0] == 6);

    stream.push(a, packet(5, 8), 150);
    stream.push(a, packet(999, 7), 151);
    CHECK(!stream.ready(190));
    CHECK(stream.ready(191));
    CHECK(stream.pop()[0] == 7);
    stream.push(b, packet(1000, 9), 450);
    CHECK(!stream.expire(450));
    CHECK(stream.expire(451));
    CHECK(!stream.ready(500));
    stream.push(b, packet(0, 8), 500);
    CHECK(stream.ready(540));
    CHECK(stream.pop()[0] == 8);

    stream = {};
    for (std::uint32_t i = 100; i < 108; ++i) stream.push(a, packet(i, 1), i);
    stream.push(a, packet(99, 2), 108);
    CHECK(stream.ready(108));
    CHECK(stream.pop()[0] == 2);
    for (int i = 0; i < 7; ++i) CHECK(stream.pop()[0] == 1);
    CHECK(stream.pop() == mwf::Pcm{});

    // El emisor del vídeo es el mismo que el de audio y no depende de la salida WASAPI.
    stream = {};
    CHECK(!stream.activePeer(500));
    CHECK(stream.push(a, packet(10, 1), 500));
    CHECK(!stream.push(b, packet(11, 2), 600));
    CHECK(!stream.push(a, packet(10, 2), 700));
    CHECK(stream.activePeer(799)->peer == a);
    CHECK(!stream.activePeer(800));
    CHECK(stream.expire(800));
    CHECK(stream.push(b, packet(0, 3), 800));
    stream.discardAudio();
    CHECK(!stream.ready(810));
    CHECK(stream.activePeer(810)->peer == b);
    CHECK(!stream.push(a, packet(11, 4), 820));
    CHECK(stream.push(b, packet(1, 5), 830));
    CHECK(stream.activePeer(1129)->peer == b);
    CHECK(!stream.activePeer(1130));
    CHECK(mwf::usableVideoIpv4(0xc0a80123)); // 192.168.1.35
    CHECK(!mwf::usableVideoIpv4(0));
    CHECK(!mwf::usableVideoIpv4(0x7f000001)); // 127.0.0.1
    CHECK(!mwf::usableVideoIpv4(0xe0000001)); // 224.0.0.1
    CHECK(!mwf::usableVideoIpv4(0xffffffff));
    std::puts("Protocolo, orden, pérdidas, ventana, reinicio y emisor reciente de vídeo: OK");
}
