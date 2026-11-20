window.updateStatus=message=>{document.getElementById("appUpdateStatus").textContent=message};
const updateButton=document.getElementById("appUpdate");
updateButton.onclick=()=>{if(window.Poster && typeof Poster.checkUpdate==="function")Poster.checkUpdate(true);else updateStatus("請在 Android App 內使用一鍵更新。");};
if(window.Poster && typeof Poster.checkUpdate==="function")Poster.checkUpdate(false);
