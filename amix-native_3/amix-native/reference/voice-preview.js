// Дословно из index.html, строки 1804-1849 (микрофон только меняет amixVoiceLevel)
(function initAmixVoicePreview(){
 let stream=null,audioContext=null,analyser=null,meterFrame=0,active=false;
 const button=()=>document.getElementById('voiceMicBtn');
 const status=()=>document.getElementById('voiceStatus');
 const dot=()=>document.getElementById('voiceStatusDot');
 function setState(state){
  const st=status(),bt=button(),d=dot();
  if(st)st.dataset.i18n=state==='active'?'voice_status_active':state==='error'?'voice_status_error':'voice_status_idle';
  if(st)st.textContent=t(st.dataset.i18n);
  if(bt){bt.dataset.i18n=state==='active'?'voice_mic_stop':'voice_mic_start';bt.textContent=t(bt.dataset.i18n)}
  if(d)d.classList.toggle('active',state==='active');
 }
 function meter(){
  if(!active||!analyser)return;
  const data=new Uint8Array(analyser.fftSize);analyser.getByteTimeDomainData(data);
  let sum=0;for(let i=0;i<data.length;i++){const v=(data[i]-128)/128;sum+=v*v}
  const rms=Math.sqrt(sum/data.length);
  const level=Math.min(1,rms*15.5);
  const previous=Number(window.amixVoiceLevel)||0;
  window.amixVoiceLevel=level>previous?previous*.38+level*.62:previous*.84+level*.16;
  meterFrame=requestAnimationFrame(meter);
 }
 async function start(){
  if(!navigator.mediaDevices||!navigator.mediaDevices.getUserMedia)throw new Error('Microphone API is unavailable');
  stream=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true}});
  const AC=window.AudioContext||window.webkitAudioContext;if(!AC)throw new Error('Web Audio is unavailable');
  audioContext=new AC();await audioContext.resume();analyser=audioContext.createAnalyser();analyser.fftSize=512;analyser.smoothingTimeConstant=.25;
  const source=audioContext.createMediaStreamSource(stream);source.connect(analyser);
  active=true;window.amixVoiceLevel=0;setState('active');meter();
 }
 async function stop(){
  active=false;if(meterFrame)cancelAnimationFrame(meterFrame);meterFrame=0;
  if(stream){stream.getTracks().forEach(track=>track.stop());stream=null}
  if(audioContext){try{await audioContext.close()}catch(e){}audioContext=null}
  analyser=null;window.amixVoiceLevel=0;setState('idle');
 }
 window.stopAmixVoiceMic=stop;
 window.toggleVoiceMic=async function(){
  if(active){await stop();return}
  const bt=button();if(bt){bt.disabled=true}
  try{await start()}
  catch(err){console.warn('Amix voice preview microphone:',err);await stop();setState('error')}
  finally{if(bt)bt.disabled=false}
 };
 document.addEventListener('visibilitychange',()=>{if(document.hidden&&active)stop()});
})();
