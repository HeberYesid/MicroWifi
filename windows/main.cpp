#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <iphlpapi.h>
#include <shellapi.h>
#include <audioclient.h>
#include <mmdeviceapi.h>
#include <propsys.h>
#include <functiondiscoverykeys_devpkey.h>
#include <wrl/client.h>

#include <algorithm>
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include "stream.hpp"

namespace {
using Microsoft::WRL::ComPtr;
constexpr UINT trayMessage = WM_APP + 1;
constexpr UINT failedMessage = WM_APP + 2;
constexpr UINT trayId = 1;

struct Com {
    HRESULT status = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    ~Com() { if (SUCCEEDED(status)) CoUninitialize(); }
};

struct Endpoint { std::wstring id, name; };

HRESULT enumerator(ComPtr<IMMDeviceEnumerator>& result) {
    return CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_INPROC_SERVER,
                            IID_PPV_ARGS(result.ReleaseAndGetAddressOf()));
}

std::vector<Endpoint> outputs() {
    std::vector<Endpoint> result;
    ComPtr<IMMDeviceEnumerator> devices;
    ComPtr<IMMDeviceCollection> collection;
    UINT count = 0;
    if (FAILED(enumerator(devices)) ||
        FAILED(devices->EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE, &collection)) ||
        FAILED(collection->GetCount(&count))) return result;
    for (UINT i = 0; i < count; ++i) {
        ComPtr<IMMDevice> device;
        ComPtr<IPropertyStore> properties;
        LPWSTR id = nullptr;
        if (FAILED(collection->Item(i, &device)) ||
            FAILED(device->OpenPropertyStore(STGM_READ, &properties)) ||
            FAILED(device->GetId(&id))) continue;
        PROPVARIANT value{};
        if (SUCCEEDED(properties->GetValue(PKEY_Device_FriendlyName, &value)) &&
            value.vt == VT_LPWSTR && value.pwszVal) result.push_back({id, value.pwszVal});
        PropVariantClear(&value);
        CoTaskMemFree(id);
    }
    return result;
}

class Audio {
    ComPtr<IAudioClient> client_;
    ComPtr<IAudioRenderClient> render_;
    UINT32 capacity_ = 0;
    bool started_ = false;

public:
    ~Audio() { close(); }

    void close() {
        if (started_) client_->Stop();
        started_ = false;
        render_.Reset();
        client_.Reset();
    }

    HRESULT open(const std::wstring& id) {
        ComPtr<IMMDeviceEnumerator> devices;
        ComPtr<IMMDevice> device;
        HRESULT hr = enumerator(devices);
        if (SUCCEEDED(hr)) hr = devices->GetDevice(id.c_str(), &device);
        if (SUCCEEDED(hr)) hr = device->Activate(__uuidof(IAudioClient), CLSCTX_INPROC_SERVER,
                                                nullptr, reinterpret_cast<void**>(client_.GetAddressOf()));
        if (FAILED(hr)) return hr;
        WAVEFORMATEX format{};
        format.wFormatTag = WAVE_FORMAT_PCM;
        format.nChannels = 1;
        format.nSamplesPerSec = mwf::sampleRate;
        format.nBlockAlign = mwf::frameBytes;
        format.nAvgBytesPerSec = mwf::sampleRate * mwf::frameBytes;
        format.wBitsPerSample = 16;
        hr = client_->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM |
                                 AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY |
                                 AUDCLNT_STREAMFLAGS_NOPERSIST,
                                 300000, 0, &format, nullptr);
        if (SUCCEEDED(hr)) hr = client_->GetBufferSize(&capacity_);
        if (SUCCEEDED(hr)) hr = client_->GetService(IID_PPV_ARGS(render_.GetAddressOf()));
        return hr;
    }

    HRESULT flush() {
        if (!started_) return S_OK;
        HRESULT hr = client_->Stop();
        started_ = false;
        return FAILED(hr) ? hr : client_->Reset();
    }

    HRESULT pump(mwf::Stream& stream, std::uint64_t now) {
        if (!stream.ready(now)) return S_OK;
        UINT32 padding = 0;
        HRESULT hr = client_->GetCurrentPadding(&padding);
        if (FAILED(hr)) return hr;
        // Máximo 20 ms en la cola de reproducción propia, independientemente del tamaño WASAPI.
        const UINT32 target = (std::min)(capacity_, 2 * mwf::frames);
        while (padding + mwf::frames <= target && stream.ready(now)) {
            BYTE* dest = nullptr;
            hr = render_->GetBuffer(mwf::frames, &dest);
            if (FAILED(hr)) return hr;
            const auto pcm = stream.pop();
            std::memcpy(dest, pcm.data(), pcm.size());
            hr = render_->ReleaseBuffer(mwf::frames, 0);
            if (FAILED(hr)) return hr;
            padding += mwf::frames;
        }
        if (!started_) {
            hr = client_->Start();
            started_ = SUCCEEDED(hr);
        }
        return hr;
    }
};

class Receiver {
    SOCKET socket_ = INVALID_SOCKET;
    std::thread thread_;
    std::atomic<bool> stop_{false};
    std::mutex lock_;
    std::wstring requested_;
    unsigned generation_ = 0;
    std::optional<mwf::RecentPeer> recent_;

    void run(HWND hwnd) {
        Com com;
        Audio audio;
        mwf::Stream stream;
        unsigned current = 0;
        bool enabled = false;
        auto failAudio = [&](HRESULT hr) {
            audio.close();
            enabled = false;
            stream.discardAudio();
            PostMessageW(hwnd, failedMessage, current, hr);
        };
        while (!stop_) {
            {
                std::unique_lock<std::mutex> guard(lock_);
                if (current != generation_) {
                    current = generation_;
                    const auto id = requested_;
                    guard.unlock();
                    audio.close();
                    stream.discardAudio();
                    enabled = !id.empty();
                    if (enabled) {
                        const auto hr = FAILED(com.status) ? com.status : audio.open(id);
                        if (FAILED(hr)) failAudio(hr);
                    }
                }
            }

            fd_set readable;
            FD_ZERO(&readable);
            FD_SET(socket_, &readable);
            timeval timeout{0, 5000};
            const int waiting = select(0, &readable, nullptr, nullptr, &timeout);
            if (waiting == SOCKET_ERROR) {
                const int error = WSAGetLastError();
                {
                    std::lock_guard<std::mutex> guard(lock_);
                    recent_.reset();
                }
                PostMessageW(hwnd, failedMessage, 0, error);
                return;
            }
            const auto now = GetTickCount64();
            if (stream.expire(now) && enabled) {
                const auto hr = audio.flush();
                if (FAILED(hr)) failAudio(hr);
            }
            bool accepted = false;
            for (int n = 0; waiting > 0 && n < 32; ++n) {
                char bytes[mwf::packetBytes];
                sockaddr_in from{};
                int size = sizeof(from);
                int received = recvfrom(socket_, bytes, sizeof(bytes), 0,
                                        reinterpret_cast<sockaddr*>(&from), &size);
                if (received == SOCKET_ERROR) {
                    if (WSAGetLastError() == WSAEMSGSIZE) continue;
                    break;
                }
                mwf::Packet packet;
                if (from.sin_family == AF_INET &&
                    mwf::decode(bytes, static_cast<std::size_t>(received), packet) &&
                    stream.push({from.sin_addr.s_addr, from.sin_port}, packet, now)) {
                    accepted = true;
                }
            }
            if (accepted) {
                std::lock_guard<std::mutex> guard(lock_);
                recent_ = stream.activePeer(now);
            }
            if (enabled) {
                const auto hr = audio.pump(stream, now);
                if (FAILED(hr)) failAudio(hr);
            }
        }
    }

public:
    ~Receiver() {
        stop_ = true;
        if (thread_.joinable()) thread_.join();
        if (socket_ != INVALID_SOCKET) closesocket(socket_);
    }

    int start(HWND hwnd) {
        socket_ = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        if (socket_ == INVALID_SOCKET) return WSAGetLastError();
        const BOOL exclusive = TRUE;
        const int buffer = mwf::packetBytes * 16;
        u_long nonblocking = 1;
        if (setsockopt(socket_, SOL_SOCKET, SO_EXCLUSIVEADDRUSE,
                       reinterpret_cast<const char*>(&exclusive), sizeof(exclusive)) == SOCKET_ERROR ||
            setsockopt(socket_, SOL_SOCKET, SO_RCVBUF,
                       reinterpret_cast<const char*>(&buffer), sizeof(buffer)) == SOCKET_ERROR ||
            ioctlsocket(socket_, FIONBIO, &nonblocking) == SOCKET_ERROR) return WSAGetLastError();
        sockaddr_in address{};
        address.sin_family = AF_INET;
        address.sin_port = htons(mwf::port);
        address.sin_addr.s_addr = htonl(INADDR_ANY);
        if (bind(socket_, reinterpret_cast<const sockaddr*>(&address), sizeof(address)) == SOCKET_ERROR)
            return WSAGetLastError();
        thread_ = std::thread(&Receiver::run, this, hwnd);
        return 0;
    }

    unsigned selectOutput(const std::wstring& id) {
        std::lock_guard<std::mutex> guard(lock_);
        requested_ = id;
        return ++generation_;
    }

    std::wstring videoUrl() {
        std::lock_guard<std::mutex> guard(lock_);
        if (!recent_ || !recent_->active(GetTickCount64())) return {};
        IN_ADDR ip{};
        ip.s_addr = recent_->peer.ip;
        if (!mwf::usableVideoIpv4(ntohl(ip.s_addr))) return {};
        wchar_t text[INET_ADDRSTRLEN]{};
        if (!InetNtopW(AF_INET, &ip, text, INET_ADDRSTRLEN)) return {};
        return std::wstring(L"http://") + text + L":" + std::to_wstring(mwf::videoPort) + L"/video";
    }
};

bool copyText(HWND hwnd, const std::wstring& text) {
    const auto bytes = (text.size() + 1) * sizeof(wchar_t);
    HGLOBAL memory = GlobalAlloc(GMEM_MOVEABLE, bytes);
    if (!memory) return false;
    void* data = GlobalLock(memory);
    if (!data) {
        GlobalFree(memory);
        return false;
    }
    std::memcpy(data, text.c_str(), bytes);
    GlobalUnlock(memory);
    bool copied = false;
    if (OpenClipboard(hwnd)) {
        copied = EmptyClipboard() && SetClipboardData(CF_UNICODETEXT, memory);
        CloseClipboard();
    }
    if (!copied) GlobalFree(memory);
    return copied;
}

std::wstring addresses() {
    ULONG bytes = 15000;
    auto adapters = std::make_unique<unsigned char[]>(bytes);
    constexpr ULONG flags = GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER;
    ULONG result = GetAdaptersAddresses(AF_INET, flags, nullptr,
                                        reinterpret_cast<IP_ADAPTER_ADDRESSES*>(adapters.get()), &bytes);
    if (result == ERROR_BUFFER_OVERFLOW) {
        adapters = std::make_unique<unsigned char[]>(bytes);
        result = GetAdaptersAddresses(AF_INET, flags, nullptr,
                                      reinterpret_cast<IP_ADAPTER_ADDRESSES*>(adapters.get()), &bytes);
    }
    if (result != NO_ERROR && result != ERROR_NO_DATA)
        return L"No se pudieron consultar los adaptadores: " + std::to_wstring(result);
    std::wstring text;
    if (result == NO_ERROR) {
        for (auto* a = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(adapters.get()); a; a = a->Next) {
            if (a->OperStatus != IfOperStatusUp || a->IfType == IF_TYPE_SOFTWARE_LOOPBACK) continue;
            for (auto* u = a->FirstUnicastAddress; u; u = u->Next) {
                auto* addr = u->Address.lpSockaddr;
                if (addr->sa_family != AF_INET) continue;
                wchar_t ip[INET_ADDRSTRLEN]{};
                if (!InetNtopW(AF_INET, &reinterpret_cast<sockaddr_in*>(addr)->sin_addr, ip, INET_ADDRSTRLEN))
                    continue;
                text += ip;
                if (a->FriendlyName) { text += L"  —  "; text += a->FriendlyName; }
                text += L'\n';
            }
        }
    }
    if (text.empty()) text = L"No hay interfaces IPv4 activas.\n";
    return text + L"\nUDP 47831. Usa la IPv4 del Wi-Fi o Ethernet conectado al mismo router que Android.";
}

struct App {
    Receiver receiver;
    std::wstring selected;
    std::wstring name;
    unsigned generation = 0;
    UINT taskbarCreated = RegisterWindowMessageW(L"TaskbarCreated");
    bool version4 = false;
    bool menuOpen = false;
    NOTIFYICONDATAW icon{};

    bool addIcon(HWND hwnd) {
        icon.cbSize = sizeof(icon);
        icon.hWnd = hwnd;
        icon.uID = trayId;
        icon.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP | NIF_SHOWTIP;
        icon.uCallbackMessage = trayMessage;
        icon.hIcon = LoadIconW(nullptr, IDI_APPLICATION);
        wcscpy_s(icon.szTip, L"MicroWifi · audio y enlace de vídeo");
        if (!Shell_NotifyIconW(NIM_ADD, &icon)) return false;
        icon.uVersion = NOTIFYICON_VERSION_4;
        version4 = !!Shell_NotifyIconW(NIM_SETVERSION, &icon);
        return true;
    }

    void video(HWND hwnd, bool copy) {
        const auto url = receiver.videoUrl();
        constexpr auto title = L"MicroWifi · vídeo del teléfono";
        if (url.empty()) {
            MessageBoxW(hwnd, L"No hay un teléfono activo. Inicia el audio en Android y mantén la transmisión "
                              L"mientras eliges esta opción. Activa también la cámara para ver el vídeo.",
                        title, MB_OK | MB_ICONINFORMATION);
        } else if (copy) {
            const bool copied = copyText(hwnd, url);
            const auto text = (copied ? L"Enlace copiado:\n\n" : L"No se pudo copiar el enlace:\n\n") + url;
            MessageBoxW(hwnd, text.c_str(), title, MB_OK | (copied ? MB_ICONINFORMATION : MB_ICONWARNING));
        } else {
            const auto result = ShellExecuteW(hwnd, L"open", url.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
            if (reinterpret_cast<INT_PTR>(result) <= 32) {
                const auto text = L"No se pudo abrir el navegador. Usa «Copiar enlace de vídeo»:\n\n" + url;
                MessageBoxW(hwnd, text.c_str(), title, MB_OK | MB_ICONWARNING);
            }
        }
    }

    void menu(HWND hwnd, POINT point) {
        if (menuOpen) return;
        menuOpen = true;
        constexpr UINT ip = 1, mute = 2, quit = 3, openVideo = 4, copyVideo = 5, first = 100;
        HMENU popup = CreatePopupMenu();
        HMENU devices = CreatePopupMenu();
        if (!popup || !devices) {
            if (popup) DestroyMenu(popup);
            if (devices) DestroyMenu(devices);
            menuOpen = false;
            return;
        }
        const auto list = outputs();
        const auto status = selected.empty() ? std::wstring(L"Salida: desactivada") : L"Salida: " + name;
        AppendMenuW(popup, MF_GRAYED | MF_STRING, 0, status.c_str());
        AppendMenuW(popup, MF_STRING, ip, L"Ver IPv4 (UDP 47831)");
        AppendMenuW(popup, MF_POPUP, reinterpret_cast<UINT_PTR>(devices), L"Dispositivo de reproducción");
        AppendMenuW(devices, MF_STRING | (selected.empty() ? MF_CHECKED : 0), mute, L"Desactivar salida");
        AppendMenuW(devices, MF_SEPARATOR, 0, nullptr);
        if (list.empty()) AppendMenuW(devices, MF_GRAYED | MF_STRING, 0, L"No hay dispositivos activos");
        for (UINT i = 0; i < list.size(); ++i) {
            std::wstring label = list[i].name;
            for (std::size_t p = 0; (p = label.find(L'&', p)) != std::wstring::npos; p += 2)
                label.insert(p, 1, L'&');
            AppendMenuW(devices, MF_STRING | (selected == list[i].id ? MF_CHECKED : 0), first + i, label.c_str());
        }
        AppendMenuW(popup, MF_SEPARATOR, 0, nullptr);
        AppendMenuW(popup, MF_STRING, openVideo, L"Abrir vídeo del teléfono en el navegador");
        AppendMenuW(popup, MF_STRING, copyVideo, L"Copiar enlace de vídeo");
        AppendMenuW(popup, MF_SEPARATOR, 0, nullptr);
        AppendMenuW(popup, MF_STRING, quit, L"Salir");
        SetForegroundWindow(hwnd);
        const UINT align = GetSystemMetrics(SM_MENUDROPALIGNMENT) ? TPM_RIGHTALIGN : TPM_LEFTALIGN;
        const auto cmd = static_cast<UINT>(TrackPopupMenu(popup, TPM_RETURNCMD | TPM_NONOTIFY |
                                                       TPM_RIGHTBUTTON | align, point.x, point.y, 0, hwnd, nullptr));
        PostMessageW(hwnd, WM_NULL, 0, 0);
        DestroyMenu(popup);
        Shell_NotifyIconW(NIM_SETFOCUS, &icon);
        if (cmd == ip) {
            MessageBoxW(hwnd, addresses().c_str(), L"MicroWifi · direcciones IPv4", MB_OK | MB_ICONINFORMATION);
        } else if (cmd == openVideo || cmd == copyVideo) {
            video(hwnd, cmd == copyVideo);
        } else if (cmd == mute) {
            generation = receiver.selectOutput(L"");
            selected.clear();
            name.clear();
        } else if (cmd == quit) {
            DestroyWindow(hwnd);
        } else if (cmd >= first && cmd - first < list.size()) {
            const auto& device = list[cmd - first];
            const auto text = L"¿Enviar el audio recibido a este dispositivo?\n\n" + device.name +
                              L"\n\nSi usas VB-CABLE, elige CABLE Input.";
            if (MessageBoxW(hwnd, text.c_str(), L"MicroWifi · confirmar salida",
                            MB_YESNO | MB_DEFBUTTON2 | MB_ICONQUESTION) == IDYES) {
                selected = device.id;
                name = device.name;
                generation = receiver.selectOutput(selected);
            }
        }
        menuOpen = false;
    }
};

LRESULT CALLBACK windowProc(HWND hwnd, UINT msg, WPARAM w, LPARAM l) {
    if (msg == WM_NCCREATE) {
        const auto* creation = reinterpret_cast<const CREATESTRUCTW*>(l);
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(creation->lpCreateParams));
    }
    auto* app = reinterpret_cast<App*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
    if (!app) return DefWindowProcW(hwnd, msg, w, l);
    if (msg == app->taskbarCreated) {
        app->addIcon(hwnd);
        return 0;
    }
    switch (msg) {
    case trayMessage: {
        const auto event = app->version4 ? LOWORD(l) : static_cast<UINT>(l);
        const bool activate = app->version4 ? (event == WM_CONTEXTMENU || event == NIN_SELECT ||
                                             event == NIN_KEYSELECT)
                                           : (event == WM_RBUTTONUP || event == WM_LBUTTONUP);
        if (activate) {
            POINT point{};
            GetCursorPos(&point);
            if (app->version4 && (event == WM_CONTEXTMENU || event == NIN_KEYSELECT)) {
                point.x = static_cast<short>(LOWORD(w));
                point.y = static_cast<short>(HIWORD(w));
            }
            app->menu(hwnd, point);
        }
        return 0;
    }
    case failedMessage:
        if (!w) {
            const auto text = L"Falló la recepción UDP: " + std::to_wstring(l);
            MessageBoxW(hwnd, text.c_str(), L"MicroWifi", MB_OK | MB_ICONERROR);
            DestroyWindow(hwnd);
        } else if (w == app->generation) {
            app->selected.clear();
            app->name.clear();
            wchar_t text[128];
            swprintf_s(text, L"Salida desactivada. Selecciona el dispositivo de nuevo.\nError WASAPI: 0x%08lX",
                       static_cast<unsigned long>(static_cast<HRESULT>(l)));
            MessageBoxW(hwnd, text, L"MicroWifi", MB_OK | MB_ICONWARNING);
        }
        return 0;
    case WM_DESTROY:
        Shell_NotifyIconW(NIM_DELETE, &app->icon);
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, w, l);
}
} // namespace

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int) {
    Com com;
    WSADATA wsa{};
    if (FAILED(com.status) || WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        MessageBoxW(nullptr, L"No se pudo iniciar Windows Audio o Winsock.", L"MicroWifi", MB_OK | MB_ICONERROR);
        return 1;
    }
    struct Winsock { ~Winsock() { WSACleanup(); } } winsock;
    App app;
    WNDCLASSW window{};
    window.lpfnWndProc = windowProc;
    window.hInstance = instance;
    window.lpszClassName = L"MicroWifiTray";
    if (!RegisterClassW(&window)) return 1;
    const auto hwnd = CreateWindowExW(WS_EX_TOOLWINDOW, window.lpszClassName, L"MicroWifi",
                                     WS_POPUP, 0, 0, 0, 0, nullptr, nullptr, instance, &app);
    if (!hwnd) return 1;
    const auto status = app.receiver.start(hwnd);
    if (status || !app.addIcon(hwnd)) {
        const auto text = status ? L"No se pudo abrir UDP 47831. Error Winsock: " + std::to_wstring(status)
                                 : L"No se pudo agregar el icono a la bandeja.";
        MessageBoxW(hwnd, text.c_str(), L"MicroWifi", MB_OK | MB_ICONERROR);
        DestroyWindow(hwnd);
        return 1;
    }
    MSG msg{};
    BOOL next;
    while ((next = GetMessageW(&msg, nullptr, 0, 0)) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    return next == -1 ? 1 : 0;
}
