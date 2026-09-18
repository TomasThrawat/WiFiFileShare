package com.tomasthrawat.wififileshare

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.UUID

data class Peer(val id:String,val name:String,val address:InetAddress)

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val id = UUID.randomUUID().toString().take(8)
    private val peers = mutableStateMapOf<String,Peer>()
    private var server: ServerSocket? = null
    private val discoveryPort=45454
    private val transferPort=45455

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        startServers()
        setContent { App() }
    }

    @Composable fun App() {
        var selected by remember { mutableStateOf<Uri?>(null) }
        var status by remember { mutableStateOf("جاهز. وصّل الجهازين بنفس Wi-Fi") }
        val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){ selected=it }
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item { Text("Wi-Fi File Share",style=MaterialTheme.typography.headlineMedium); Text(status) }
                    item { Button({picker.launch(arrayOf("*/*"))},Modifier.fillMaxWidth()){Text(if(selected==null)"اختيار ملف" else "تغيير الملف")} }
                    item { Button({ discover(); status="جاري البحث..." },Modifier.fillMaxWidth()){Text("البحث عن الهواتف")}}
                    if(selected!=null) item { Card(Modifier.fillMaxWidth()){Text("الملف: "+nameOf(selected!!),Modifier.padding(16.dp))}}
                    item { Text("الهواتف على نفس الشبكة",style=MaterialTheme.typography.titleLarge) }
                    items(peers.values.toList(),key={it.id}) { p ->
                        Button({ if(selected==null) status="اختر ملفًا أولًا" else scope.launch { send(selected!!,p){status=it} }},Modifier.fillMaxWidth()){
                            Text(p.name+"\n"+p.address.hostAddress)
                        }
                    }
                }
            }
        }
    }

    private fun startServers() {
        scope.launch {
            server=ServerSocket(transferPort)
            while (isActive) { val s=server!!.accept(); launch { receive(s) } }
        }
        scope.launch {
            DatagramSocket(discoveryPort).use { ds ->
                ds.soTimeout=1000
                val b=ByteArray(2048)
                while(isActive) try {
                    val p=DatagramPacket(b,b.size); ds.receive(p)
                    val x=String(p.data,0,p.length,StandardCharsets.UTF_8).split("|",limit=3)
                    if(x.size==3 && x[0]=="WFS" && x[1]!=id) peers[x[1]]=Peer(x[1],x[2],p.address)
                } catch(_:SocketTimeoutException){}
            }
        }
        scope.launch {
            while(isActive){ broadcast(); delay(2000) }
        }
    }

    private fun discover(){ scope.launch { repeat(5){broadcast();delay(300)} } }

    private fun broadcast() {
        try { DatagramSocket().use { ds ->
            ds.broadcast=true
            val m=("WFS|"+id+"|"+android.os.Build.MODEL).toByteArray(StandardCharsets.UTF_8)
            ds.send(DatagramPacket(m,m.size,InetAddress.getByName("255.255.255.255"),discoveryPort))
        }} catch(_:Exception){}
    }

    private suspend fun send(uri:Uri,p:Peer,set:(String)->Unit)=withContext(Dispatchers.IO){
        try {
            Socket().use { s ->
                s.tcpNoDelay=true; s.sendBufferSize=4*1024*1024; s.receiveBufferSize=4*1024*1024
                s.connect(InetSocketAddress(p.address,transferPort),3000)
                DataOutputStream(BufferedOutputStream(s.getOutputStream(),4*1024*1024)).use { out ->
                    val n=nameOf(uri).toByteArray(StandardCharsets.UTF_8); val size=sizeOf(uri)
                    out.writeInt(n.size);out.write(n);out.writeLong(size)
                    contentResolver.openInputStream(uri)!!.use { input ->
                        BufferedInputStream(input,4*1024*1024).use { bi ->
                            val buf=ByteArray(1024*1024);var sent=0L;var c:Int
                            while(bi.read(buf).also{c=it}>0){out.write(buf,0,c);sent+=c;if(sent%(8L*1024*1024)<c)withContext(Dispatchers.Main){set("إرسال: %.1f / %.1f MB".format(sent/1048576.0,size/1048576.0))}}
                        }
                    }
                    out.flush()
                }
            }
            withContext(Dispatchers.Main){set("تم الإرسال عبر Wi-Fi")}
        } catch(e:Exception){withContext(Dispatchers.Main){set("فشل الإرسال: "+(e.message?:"خطأ شبكة"))}}
    }

    private fun receive(s:Socket) {
        try {
            s.tcpNoDelay=true
            DataInputStream(BufferedInputStream(s.getInputStream(),4*1024*1024)).use { input ->
                val len=input.readInt();require(len in 1..255);val nb=ByteArray(len);input.readFully(nb);val name=String(nb,StandardCharsets.UTF_8);val size=input.readLong()
                val cv=ContentValues().apply{put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream");put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/WiFiFileShare");put(MediaStore.Downloads.IS_PENDING,1)}
                val uri=contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv)?:error("storage")
                try {
                    contentResolver.openOutputStream(uri)!!.use{raw->BufferedOutputStream(raw,4*1024*1024).use{out->
                        val buf=ByteArray(1024*1024);var left=size
                        while(left>0){val c=input.read(buf,0,minOf(buf.size.toLong(),left).toInt());if(c<0)error("connection closed");out.write(buf,0,c);left-=c};out.flush()
                    }}
                    contentResolver.update(uri,ContentValues().apply{put(MediaStore.Downloads.IS_PENDING,0)},null,null)
                } catch(e:Exception){contentResolver.delete(uri,null,null);throw e}
            }
        } catch(_:Exception){} finally{try{s.close()}catch(_:Exception){}}
    }

    private fun nameOf(uri:Uri)=contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0) else "file"}?:"file"
    private fun sizeOf(uri:Uri)=contentResolver.openAssetFileDescriptor(uri,"r")?.use{it.length}?:-1L

    override fun onDestroy(){scope.cancel();try{server?.close()}catch(_:Exception){};super.onDestroy()}
}