package com.armsone.nasfinder.platform

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Minimal one-request-per-connection HTTP server for PhoneHard. */
class WebHardHttpServer(
    private val store: WebHardFileStore,
    private val password: String = "",
    private val bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
    private val requestedPort: Int = 0,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val clients: ExecutorService = Executors.newFixedThreadPool(MAX_CLIENT_WORKERS) { work ->
        Thread(work, "NasFinder-WebHard-client").apply { isDaemon = true }
    }
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null

    val localPort: Int get() = listener?.localPort ?: 0

    @Synchronized
    fun start(): Int {
        check(!closed.get()) { "종료된 폰하드 서버는 다시 시작할 수 없습니다." }
        if (running.get()) return localPort
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, requestedPort))
        }
        listener = socket
        running.set(true)
        acceptThread = Thread({ acceptLoop(socket) }, "NasFinder-WebHard-accept").apply {
            isDaemon = true
            start()
        }
        return socket.localPort
    }

    private fun acceptLoop(serverSocket: ServerSocket) {
        while (running.get()) {
            try {
                val socket = serverSocket.accept().apply { soTimeout = SOCKET_TIMEOUT_MS }
                activeSockets += socket
                try {
                    clients.execute { handle(socket) }
                } catch (error: RejectedExecutionException) {
                    activeSockets -= socket
                    runCatching { socket.close() }
                    if (running.get()) throw error
                    return
                }
            } catch (_: Exception) {
                if (running.get()) continue
                return
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { client ->
                val input = BufferedInputStream(client.getInputStream())
                val output = BufferedOutputStream(client.getOutputStream())
                try {
                    val request = readRequest(input)
                    route(request, input, output)
                } catch (error: HttpFailure) {
                    sendJson(output, error.status, error.message ?: "요청을 처리하지 못했습니다.")
                } catch (error: WebHardFileStoreException.NotFound) {
                    sendJson(output, 404, error.message.orEmpty())
                } catch (error: WebHardFileStoreException) {
                    sendJson(output, 400, error.message.orEmpty())
                } catch (_: Throwable) {
                    sendJson(output, 500, "요청을 처리하지 못했습니다.")
                }
                runCatching { output.flush() }
            }
        } finally {
            activeSockets -= socket
        }
    }

    private fun route(request: HttpRequest, input: BufferedInputStream, output: BufferedOutputStream) {
        val target = parseTarget(request.target)
        if (request.method == "GET" && target.path == "/") {
            sendBytes(output, 200, "text/html; charset=utf-8", HOME_PAGE.toByteArray())
            return
        }
        if (!authorized(request, target.query["password"])) throw HttpFailure(401, "비밀번호가 올바르지 않습니다.")
        val requestedPath = target.query["path"] ?: "/"

        when (request.method to target.path) {
            "GET" to "/api/list" -> {
                val body = store.list(requestedPath).joinToString(prefix = "[", postfix = "]") { it.toJson() }
                sendBytes(output, 200, "application/json; charset=utf-8", body.toByteArray())
            }
            "GET" to "/api/file" -> sendFile(output, store.file(requestedPath), attachment = true)
            "GET" to "/api/preview" -> sendFile(output, store.file(requestedPath), attachment = false)
            "POST" to "/api/folder" -> {
                store.createDirectory(requestedPath)
                sendJson(output, 201, "폴더를 만들었습니다.")
            }
            "DELETE" to "/api/item" -> {
                store.delete(requestedPath)
                sendJson(output, 200, "삭제했습니다.")
            }
            "PUT" to "/api/file" -> receiveUpload(request, requestedPath, input, output)
            else -> throw HttpFailure(404, "요청한 기능을 찾을 수 없습니다.")
        }
    }

    private fun receiveUpload(
        request: HttpRequest,
        path: String,
        input: BufferedInputStream,
        output: BufferedOutputStream,
    ) {
        val length = request.headers["content-length"]?.toLongOrNull()
            ?: throw HttpFailure(411, "업로드 크기 정보가 필요합니다.")
        if (length < 0) throw HttpFailure(400, "올바르지 않은 업로드 크기입니다.")
        val available = store.rootDirectory.usableSpace
        if (length > (available - STORAGE_RESERVE_BYTES).coerceAtLeast(0)) {
            throw HttpFailure(507, "기기 저장공간이 부족합니다.")
        }

        val upload = store.prepareUpload(path)
        try {
            val destination = upload.outputStream()
            var remaining = length
            val buffer = ByteArray(FILE_CHUNK_BYTES)
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) throw HttpFailure(400, "업로드 크기가 요청과 다릅니다.")
                destination.write(buffer, 0, count)
                remaining -= count
            }
            if (input.available() > 0) throw HttpFailure(400, "업로드 크기가 요청보다 큽니다.")
            destination.flush()
            val item = store.commitUpload(upload)
            sendJson(output, 201, item.name)
        } catch (error: Throwable) {
            store.discardUpload(upload)
            throw error
        }
    }

    private fun authorized(request: HttpRequest, queryPassword: String?): Boolean {
        if (password.isEmpty()) return true
        val supplied = request.headers["x-webhard-password"] ?: queryPassword ?: return false
        return MessageDigest.isEqual(
            password.toByteArray(StandardCharsets.UTF_8),
            supplied.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun sendFile(output: BufferedOutputStream, file: File, attachment: Boolean) {
        val type = Files.probeContentType(file.toPath()) ?: "application/octet-stream"
        val extra = if (attachment) {
            mapOf("Content-Disposition" to "attachment; filename*=UTF-8''${urlEncode(file.name)}")
        } else emptyMap()
        writeHead(output, 200, type, file.length(), extra)
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(FILE_CHUNK_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
    }

    private fun sendJson(output: BufferedOutputStream, status: Int, message: String) {
        val body = "{\"message\":${jsonString(message)}}".toByteArray(StandardCharsets.UTF_8)
        sendBytes(output, status, "application/json; charset=utf-8", body)
    }

    private fun sendBytes(output: BufferedOutputStream, status: Int, type: String, body: ByteArray) {
        writeHead(output, status, type, body.size.toLong())
        output.write(body)
    }

    private fun writeHead(
        output: BufferedOutputStream,
        status: Int,
        type: String,
        length: Long,
        extra: Map<String, String> = emptyMap(),
    ) {
        val reason = when (status) {
            200 -> "OK"; 201 -> "Created"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; 411 -> "Length Required"; 507 -> "Insufficient Storage"
            else -> "Internal Server Error"
        }
        val lines = mutableListOf(
            "HTTP/1.1 $status $reason",
            "Content-Type: $type",
            "Content-Length: $length",
            "Cache-Control: no-store",
            "X-Content-Type-Options: nosniff",
            "Connection: close",
        )
        extra.forEach { (key, value) -> lines += "$key: $value" }
        output.write((lines.joinToString("\r\n") + "\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun readRequest(input: BufferedInputStream): HttpRequest {
        val bytes = ArrayList<Byte>()
        var matched = 0
        while (bytes.size < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) throw HttpFailure(400, "HTTP 요청이 완전하지 않습니다.")
            bytes += value.toByte()
            matched = when {
                matched == 0 && value == '\r'.code -> 1
                matched == 1 && value == '\n'.code -> 2
                matched == 2 && value == '\r'.code -> 3
                matched == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) break
        }
        if (matched != 4) throw HttpFailure(400, "HTTP 헤더가 너무 큽니다.")
        val text = bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
        val lines = text.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ') ?: emptyList()
        if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) {
            throw HttpFailure(400, "HTTP 요청 형식이 올바르지 않습니다.")
        }
        val method = requestLine[0].uppercase(Locale.US)
        if (method !in setOf("GET", "POST", "PUT", "DELETE")) throw HttpFailure(404, "지원하지 않는 요청입니다.")
        val headers = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) throw HttpFailure(400, "HTTP 헤더 형식이 올바르지 않습니다.")
            headers[line.substring(0, separator).trim().lowercase(Locale.US)] = line.substring(separator + 1).trim()
        }
        if (headers["transfer-encoding"] != null) throw HttpFailure(400, "분할 업로드는 지원하지 않습니다.")
        return HttpRequest(method, requestLine[1], headers)
    }

    private fun parseTarget(raw: String): ParsedTarget {
        val path = raw.substringBefore('?')
        if (!path.startsWith('/')) throw HttpFailure(400, "올바른 요청 주소가 아닙니다.")
        val query = linkedMapOf<String, String>()
        raw.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.forEach { pair ->
            val key = runCatching { urlDecode(pair.substringBefore('=')) }
                .getOrElse { throw HttpFailure(400, "올바르지 않은 URL 인코딩입니다.") }
            val value = runCatching { urlDecode(pair.substringAfter('=', "")) }
                .getOrElse { throw HttpFailure(400, "올바르지 않은 URL 인코딩입니다.") }
            query.putIfAbsent(key, value)
        }
        return ParsedTarget(path, query)
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        runCatching { listener?.close() }
        listener = null
        acceptThread?.interrupt()
        acceptThread = null
        activeSockets.forEach { runCatching { it.close() } }
        activeSockets.clear()
        clients.shutdownNow()
    }

    private data class HttpRequest(val method: String, val target: String, val headers: Map<String, String>)
    private data class ParsedTarget(val path: String, val query: Map<String, String>)
    private class HttpFailure(val status: Int, override val message: String) : Exception(message)

    private companion object {
        const val MAX_HEADER_BYTES = 64 * 1024
        const val FILE_CHUNK_BYTES = 256 * 1024
        const val STORAGE_RESERVE_BYTES = 50L * 1024 * 1024
        const val SOCKET_TIMEOUT_MS = 60_000
        const val MAX_CLIENT_WORKERS = 4
        val HOME_PAGE = """
            <!doctype html>
            <html lang="ko">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
              <title>NasFinder PhoneHard</title>
              <style>
                :root{color-scheme:light dark;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;--bg:#ece9e2;--card:#fbfaf6;--text:#161819;--muted:#62666a;--line:#c5c7c6;--accent:#e41e25;--orange:#ff7900;--charcoal:#303336}
                @media(prefers-color-scheme:dark){:root{--bg:#111315;--card:#26292b;--text:#f5f6f7;--muted:#b5b8ba;--line:#4a4e51;--accent:#ef4248}}
                *{box-sizing:border-box}body{margin:0;background:linear-gradient(145deg,color-mix(in srgb,var(--card) 35%,var(--bg)),var(--bg));color:var(--text)}button,input{font:inherit}button{border:1px solid var(--line);border-radius:10px;background:linear-gradient(var(--card),color-mix(in srgb,var(--card) 84%,#777));color:var(--text);padding:.65rem .8rem;cursor:pointer;box-shadow:0 3px 8px #0002,inset 0 1px #fff5}button.primary{background:linear-gradient(#464a4d,#25282a);border-color:#6c7175;color:#fff}button:disabled{opacity:.45;cursor:default}
                header{position:sticky;top:0;z-index:5;background:color-mix(in srgb,var(--card) 92%,transparent);backdrop-filter:blur(14px);padding:max(12px,env(safe-area-inset-top)) 14px 10px;border-bottom:1px solid var(--line);box-shadow:0 8px 22px #0001}.brand{display:flex;align-items:center;gap:10px;margin-bottom:10px}.phonehard-mark{display:grid;gap:1px;width:40px;height:40px;padding:4px;border:2px solid #8f9498;border-radius:11px;background:linear-gradient(145deg,#414548,#111315);box-shadow:0 4px 10px #0004,inset 0 1px #fff8;font-weight:900;line-height:1}.phonehard-mark .phone{align-self:end;color:#fff;font-size:9px}.phonehard-mark .hard{display:grid;place-items:center;height:15px;border-radius:4px;background:linear-gradient(#ff941d,#f34d00);color:#161819;font-size:11px;box-shadow:inset 0 1px #fff7}h1{font-size:1.05rem;margin:0}.toolbar{display:flex;gap:7px;align-items:center;flex-wrap:wrap}.toolbar .spacer{flex:1}.view.active{border-color:var(--orange);color:var(--orange)}
                main{max-width:1100px;margin:auto;padding:14px}.crumb{color:var(--muted);font-size:.86rem;overflow-wrap:anywhere;margin-bottom:10px}.drop{border:2px dashed var(--line);border-radius:14px;min-height:180px;padding:10px;transition:.15s}.drop.over{border-color:var(--accent);background:color-mix(in srgb,var(--accent) 8%,transparent)}
                #items{display:grid;gap:8px}.item{position:relative;border:1px solid var(--line);background:var(--card);border-radius:12px;min-width:0;cursor:pointer;box-shadow:0 7px 18px #0001}.item.selected{outline:2px solid var(--orange)}.item input{position:absolute;top:10px;left:10px;z-index:2;width:20px;height:20px;accent-color:var(--orange)}.thumb{display:grid;place-items:center;background:color-mix(in srgb,var(--muted) 10%,transparent);overflow:hidden}.thumb img,.thumb video{width:100%;height:100%;object-fit:cover}.folderIcon{font-size:2rem}.meta{min-width:0;padding:10px}.name{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.sub{font-size:.78rem;color:var(--muted);margin-top:3px}
                #items.list .item{display:grid;grid-template-columns:80px 1fr;min-height:72px}#items.list .thumb{border-radius:11px 0 0 11px}#items.small{grid-template-columns:repeat(auto-fill,minmax(130px,1fr))}#items.poster{grid-template-columns:repeat(auto-fill,minmax(210px,1fr))}#items.small .thumb{height:110px;border-radius:11px 11px 0 0}#items.poster .thumb{height:190px;border-radius:11px 11px 0 0}
                .empty{text-align:center;color:var(--muted);padding:50px 10px}.transfers{position:fixed;right:14px;bottom:14px;z-index:7;width:min(360px,calc(100vw - 28px));display:grid;gap:7px}.transfer{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:10px;box-shadow:0 8px 24px #0002}.transfer progress{width:100%}.transferHead{display:flex;gap:8px}.transferHead span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1}
                dialog{width:min(620px,calc(100vw - 28px));max-height:80vh;border:1px solid var(--line);border-radius:16px;background:var(--card);color:var(--text);padding:0}dialog::backdrop{background:#0008}.dialogHead,.dialogFoot{display:flex;gap:8px;align-items:center;padding:14px;border-bottom:1px solid var(--line)}.dialogFoot{border-top:1px solid var(--line);border-bottom:0;justify-content:flex-end}.dialogHead strong{flex:1}.queue{padding:8px 14px;max-height:45vh;overflow:auto}.queueRow{display:flex;gap:8px;padding:8px 0;border-bottom:1px solid var(--line)}.queueRow span:first-child{flex:1;overflow-wrap:anywhere}.hidden{display:none!important}
                @media(max-width:560px){button{padding:.55rem .65rem}.label{display:none}#items.poster{grid-template-columns:repeat(2,minmax(0,1fr))}#items.small{grid-template-columns:repeat(3,minmax(0,1fr))}#items.small .meta{padding:7px}.sub{display:none}}
              </style>
            </head>
            <body>
              <header><div class="brand"><div class="phonehard-mark" aria-hidden="true"><span class="phone">Phone</span><span class="hard">Hard</span></div><h1>NasFinder PhoneHard</h1></div><div class="toolbar">
                <button id="up" title="상위 폴더">↑ <span class="label">위로</span></button>
                <button id="upload" class="primary">＋ <span class="label">보내기</span></button>
                <button id="receive" disabled>↓ <span class="label">선택 받기</span></button>
                <button id="mkdir">▣ <span class="label">새 폴더</span></button><span class="spacer"></span>
                <button class="view" data-view="list" title="목록">☰</button><button class="view" data-view="small" title="작게">▦</button><button class="view" data-view="poster" title="포스터">▥</button>
              </div></header>
              <main><div id="crumb" class="crumb">/</div><div id="status" class="crumb" role="status"></div><section id="drop" class="drop"><div id="items" class="small"></div></section></main>
              <div id="transfers" class="transfers"></div>
              <dialog id="uploadQueue"><div class="dialogHead"><strong>업로드 대기열</strong><button id="pickFiles">파일 추가</button><button id="pickFolder">폴더 추가</button><button id="queueClose">닫기</button></div><div id="queue" class="queue"></div><div class="dialogFoot"><span id="queueTotal"></span><button id="queueClear">비우기</button><button id="queueStart" class="primary">업로드</button></div></dialog>
              <input id="fileInput" class="hidden" type="file" multiple><input id="folderInput" class="hidden" type="file" multiple webkitdirectory directory>
              <script>
                (function(){
                  'use strict';
                  var current='/';var entries=[];var selected=new Map();var pending=[];var password=sessionStorage.getItem('webHardPassword')||'';var view=localStorage.getItem('phoneHardView')||'small';
                  var items=document.getElementById('items'),drop=document.getElementById('drop'),queue=document.getElementById('queue'),dialog=document.getElementById('uploadQueue');
                  function join(base,name){return (base==='/'?'/':base+'/')+name.replace(/^\/+/, '');}
                  function parent(path){if(path==='/')return '/';var parts=path.split('/');parts.pop();return parts.join('/')||'/';}
                  function api(route,path){var value=route+'?path='+encodeURIComponent(path);if(password)value+='&password='+encodeURIComponent(password);return value;}
                  async function call(route,path,options){var response=await fetch(api(route,path),options||{});if(response.status===401){var supplied=prompt('폰하드 비밀번호를 입력하세요.');if(supplied!==null){password=supplied;sessionStorage.setItem('webHardPassword',password);return call(route,path,options);}throw new Error('인증이 필요합니다.');}if(!response.ok){var message='요청을 처리하지 못했습니다.';try{message=(await response.json()).message||message;}catch(ignore){}throw new Error(message);}return response;}
                  function sizeText(value){if(value===null||value===undefined)return '';var unit=['B','KB','MB','GB','TB'],index=0,number=Number(value);while(number>=1024&&index<unit.length-1){number/=1024;index++;}return number.toFixed(index?1:0)+' '+unit[index];}
                  function isImage(name){return /\.(jpg|jpeg|png|gif|webp|heic|heif|bmp)$/i.test(name);}
                  function isVideo(name){return /\.(mp4|mov|m4v|webm)$/i.test(name);}
                  function setView(next){view=next;localStorage.setItem('phoneHardView',view);items.className=view;document.querySelectorAll('.view').forEach(function(button){button.classList.toggle('active',button.dataset.view===view);});render();}
                  function preview(entry,box){if(entry.isDirectory){var icon=document.createElement('span');icon.className='folderIcon';icon.textContent='📁';box.appendChild(icon);return;}if(isImage(entry.name)){var image=document.createElement('img');image.loading='lazy';image.alt='';image.src=api('/api/preview',entry.path);box.appendChild(image);return;}if(isVideo(entry.name)){var video=document.createElement('video');video.preload='metadata';video.muted=true;video.playsInline=true;video.src=api('/api/preview',entry.path);box.appendChild(video);return;}var icon=document.createElement('span');icon.className='folderIcon';icon.textContent='📄';box.appendChild(icon);}
                  function render(){items.textContent='';items.className=view;if(!entries.length){var empty=document.createElement('div');empty.className='empty';empty.textContent='폴더가 비어 있습니다. 파일이나 폴더를 끌어 놓을 수 있습니다.';items.appendChild(empty);return;}entries.forEach(function(entry){var card=document.createElement('article');card.className='item'+(selected.has(entry.path)?' selected':'');card.title=entry.name;var check=document.createElement('input');check.type='checkbox';check.checked=selected.has(entry.path);check.setAttribute('aria-label',entry.name+' 선택');check.addEventListener('click',function(event){event.stopPropagation();toggle(entry);});var thumb=document.createElement('div');thumb.className='thumb';preview(entry,thumb);var meta=document.createElement('div');meta.className='meta';var name=document.createElement('div');name.className='name';name.textContent=entry.name;var sub=document.createElement('div');sub.className='sub';sub.textContent=entry.isDirectory?'폴더':sizeText(entry.size);meta.append(name,sub);card.append(check,thumb,meta);card.addEventListener('click',function(){if(entry.isDirectory)load(entry.path);else window.open(api('/api/preview',entry.path),'_blank','noopener');});card.addEventListener('contextmenu',function(event){event.preventDefault();removeEntry(entry);});var hold;card.addEventListener('touchstart',function(){hold=setTimeout(function(){removeEntry(entry);},650);},{passive:true});['touchend','touchmove','touchcancel'].forEach(function(kind){card.addEventListener(kind,function(){clearTimeout(hold);},{passive:true});});items.appendChild(card);});}
                  function toggle(entry){if(selected.has(entry.path))selected.delete(entry.path);else selected.set(entry.path,entry);var receive=document.getElementById('receive');receive.disabled=selected.size===0;receive.lastElementChild.textContent=selected.size?'선택 받기 ('+selected.size+')':'선택 받기';render();}
                  async function load(path){try{entries=await (await call('/api/list',path)).json();current=path;selected.clear();var receive=document.getElementById('receive');receive.disabled=true;receive.lastElementChild.textContent='선택 받기';document.getElementById('crumb').textContent=path;document.getElementById('up').disabled=path==='/';render();}catch(error){alert(error.message);}}
                  async function removeEntry(entry){if(!confirm(entry.name+' 항목을 삭제할까요?'))return;try{await call('/api/item',entry.path,{method:'DELETE'});await load(current);}catch(error){alert(error.message);}}
                  function queueFiles(files,prefix){Array.from(files).forEach(function(file){var relative=file.webkitRelativePath||file.name;pending.push({file:file,path:(prefix||'')+relative});});renderQueue();}
                  function renderQueue(){queue.textContent='';var total=0;pending.forEach(function(item,index){if(item.file)total+=item.file.size;var row=document.createElement('div');row.className='queueRow';var name=document.createElement('span');name.textContent=(item.directory?'📁 ':'')+item.path;var size=document.createElement('span');size.textContent=item.file?sizeText(item.file.size):'폴더';var remove=document.createElement('button');remove.textContent='×';remove.addEventListener('click',function(){pending.splice(index,1);renderQueue();});row.append(name,size,remove);queue.appendChild(row);});document.getElementById('queueTotal').textContent=pending.length+'개 · '+sizeText(total);document.getElementById('queueStart').disabled=pending.length===0;}
                  function transfer(name){var card=document.createElement('div');card.className='transfer';var head=document.createElement('div');head.className='transferHead';var label=document.createElement('span');label.textContent=name;var percent=document.createElement('b');percent.textContent='0%';var progress=document.createElement('progress');progress.max=100;progress.value=0;head.append(label,percent);card.append(head,progress);document.getElementById('transfers').appendChild(card);return{update:function(value){progress.value=value;percent.textContent=Math.round(value)+'%';},done:function(){setTimeout(function(){card.remove();},1200);}};}
                  function uploadOne(item){return new Promise(function(resolve,reject){var task=transfer(item.path);var xhr=new XMLHttpRequest();xhr.open('PUT',api('/api/file',join(current,item.path)));if(password)xhr.setRequestHeader('X-WebHard-Password',password);xhr.upload.onprogress=function(event){if(event.lengthComputable)task.update(event.loaded/event.total*100);};xhr.onload=function(){if(xhr.status>=200&&xhr.status<300){task.update(100);task.done();resolve();}else reject(new Error('업로드 실패: '+item.path));};xhr.onerror=function(){reject(new Error('네트워크 오류: '+item.path));};xhr.send(item.file);});}
                  async function startUploads(){var batch=pending.slice();if(!batch.length)return;document.getElementById('queueStart').disabled=true;document.getElementById('status').textContent=batch.length+'개 올리는 중…';dialog.close();try{for(var index=0;index<batch.length;index++){if(batch[index].directory)await call('/api/folder',join(current,batch[index].path),{method:'POST'});else await uploadOne(batch[index]);pending.shift();renderQueue();}await load(current);document.getElementById('status').textContent=batch.length+'개 올림';}catch(error){document.getElementById('status').textContent=error.message;dialog.showModal();}}
                  function readEntry(entry,prefix,done){if(entry.isFile){entry.file(function(file){pending.push({file:file,path:prefix+file.name});done();},done);return;}var folderPath=prefix+entry.name;pending.push({directory:true,path:folderPath});var reader=entry.createReader(),folderPrefix=folderPath+'/';function batch(){reader.readEntries(function(children){if(!children.length){done();return;}var left=children.length;children.forEach(function(child){readEntry(child,folderPrefix,function(){left--;if(left===0)batch();});});},done);}batch();}
                  function dropped(data){var list=Array.from(data.items||[]),usable=list.filter(function(item){return item.kind==='file';});if(!usable.length){queueFiles(data.files);dialog.showModal();return;}var left=usable.length;usable.forEach(function(item){var entry=item.webkitGetAsEntry&&item.webkitGetAsEntry();if(!entry){queueFiles([item.getAsFile()]);left--;if(left===0){renderQueue();dialog.showModal();}return;}readEntry(entry,'',function(){left--;if(left===0){renderQueue();dialog.showModal();}});});}
                  async function flatten(entry,result){if(!entry.isDirectory){result.push(entry);return;}var children=await (await call('/api/list',entry.path)).json();for(var index=0;index<children.length;index++)await flatten(children[index],result);}
                  async function receiveSelected(){var files=[];try{var chosen=Array.from(selected.values());for(var index=0;index<chosen.length;index++)await flatten(chosen[index],files);for(var fileIndex=0;fileIndex<files.length;fileIndex++){var anchor=document.createElement('a');anchor.href=api('/api/file',files[fileIndex].path);anchor.download=files[fileIndex].name;document.body.appendChild(anchor);anchor.click();anchor.remove();await new Promise(function(resolve){setTimeout(resolve,180);});}}catch(error){alert(error.message);}}
                  document.getElementById('up').onclick=function(){load(parent(current));};document.getElementById('upload').onclick=function(){renderQueue();dialog.showModal();};document.getElementById('receive').onclick=receiveSelected;document.getElementById('mkdir').onclick=async function(){var name=prompt('새 폴더 이름');if(!name)return;try{await call('/api/folder',join(current,name),{method:'POST'});await load(current);}catch(error){alert(error.message);}};
                  document.querySelectorAll('.view').forEach(function(button){button.onclick=function(){setView(button.dataset.view);};});document.getElementById('pickFiles').onclick=function(){document.getElementById('fileInput').click();};document.getElementById('pickFolder').onclick=function(){document.getElementById('folderInput').click();};document.getElementById('fileInput').onchange=function(event){queueFiles(event.target.files);renderQueue();event.target.value='';};document.getElementById('folderInput').onchange=function(event){queueFiles(event.target.files);renderQueue();event.target.value='';};document.getElementById('queueClose').onclick=function(){dialog.close();};document.getElementById('queueClear').onclick=function(){pending=[];renderQueue();};document.getElementById('queueStart').onclick=startUploads;
                  ['dragenter','dragover'].forEach(function(name){drop.addEventListener(name,function(event){event.preventDefault();drop.classList.add('over');});});['dragleave','drop'].forEach(function(name){drop.addEventListener(name,function(event){event.preventDefault();drop.classList.remove('over');});});drop.addEventListener('drop',function(event){dropped(event.dataTransfer);});
                  setView(view);load('/');
                }());
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}

private fun WebHardFileItem.toJson(): String = buildString {
    append('{')
    append("\"name\":").append(jsonString(name)).append(',')
    append("\"path\":").append(jsonString(path)).append(',')
    append("\"isDirectory\":").append(isDirectory).append(',')
    append("\"size\":").append(size ?: "null").append(',')
    append("\"modifiedAt\":").append(modifiedAt?.let { jsonString(DateTimeFormatter.ISO_INSTANT.format(it)) } ?: "null")
    append('}')
}

private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\b' -> append("\\b")
            '\u000C' -> append("\\f"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        }
    }
    append('"')
}

private fun urlDecode(value: String): String = try {
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
} catch (_: IllegalArgumentException) {
    throw IllegalArgumentException("올바르지 않은 URL 인코딩입니다.")
}

private fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
