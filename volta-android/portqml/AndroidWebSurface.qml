// Native Android WebView behind the existing Volta browser/web-app controls.
import QtQuick
import QtWebView
import VoltaPort 1.0
Item {
 id: surface
 required property var bridge
 function runJavaScript(script,callback){web.runJavaScript(script,function(value){if(typeof callback==="function")callback(value)})}
 function reload(){surface.bridge.portError="";AndroidPort.prepareWebViews();web.reload()}
 function stop(){web.stop()}
 function goBack(){web.goBack()}
 function goForward(){web.goForward()}
 function loadHtml(html,base){web.loadHtml(html,base||"")}
 WebView {
  id:web
  anchors.fill:parent
  visible: surface.visible && surface.bridge.portVisible && surface.bridge.lifecycleState!==2 && surface.bridge.portError===""
  settings.javaScriptEnabled: surface.bridge.settings.javascriptEnabled
  settings.localStorageEnabled: surface.bridge.settings.localStorageEnabled
  settings.allowFileAccess:false
  settings.localContentCanAccessFileUrls:false
  onUrlChanged:surface.bridge.portUrlChanged(url)
  onTitleChanged:surface.bridge.title=title
  onLoadProgressChanged:surface.bridge.loadProgress=loadProgress
  onLoadingChanged:function(request){
   surface.bridge.portError=request.status===WebView.LoadFailedStatus?request.errorString:""
   surface.bridge.portLoading=request.status===WebView.LoadStartedStatus
   surface.bridge.canGoBack=canGoBack;surface.bridge.canGoForward=canGoForward
   surface.bridge.loadingChanged({status:request.status,errorString:request.errorString,url:request.url})
   if(request.status===WebView.LoadSucceededStatus){
    var scripts=surface.bridge.userScripts.collection||[]
    for(var i=0;i<scripts.length;i++)if(scripts[i]&&scripts[i].name!=="uaFix"&&scripts[i].sourceCode)web.runJavaScript(scripts[i].sourceCode)
   }
  }
 }
 Timer {interval:100;running:true;repeat:false;onTriggered:{AndroidPort.prepareWebViews();web.url=surface.bridge.url||"about:blank"}}
 Connections {target:surface.bridge;function onUrlChanged(){if(String(web.url)!==String(surface.bridge.url))web.url=surface.bridge.url}}
}
