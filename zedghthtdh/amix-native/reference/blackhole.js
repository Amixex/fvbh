// Дословно из index.html, строки 2022-2133 (эталон анимации «чёрная дыра»)
(function initAmixBlackHole(){
 const canvas=document.getElementById('blackHoleCanvas');
 if(!canvas) return;
 const ctx=canvas.getContext('2d',{alpha:true});
 if(!ctx) return;
 let w=0,h=0,dpr=1,t=0,last=0,frame=0,stars=[],plasma=[],voiceExpansion=0,voiceDelay=0;
 const reduce=window.matchMedia('(prefers-reduced-motion: reduce)').matches;
 const rand=(a,b)=>a+Math.random()*(b-a);
 function point(){
  const target=document.body.classList.contains('voice-mode')?1:0;
  voiceExpansion += (target-voiceExpansion)*.045;
  if(Math.abs(target-voiceExpansion)<.001)voiceExpansion=target;
  const normal=w<700?{x:w*.74,y:h*.39}:{x:w*.715,y:h*.50};
  return {x:normal.x+(w*.5-normal.x)*voiceExpansion,y:normal.y+(h*.5-normal.y)*voiceExpansion};
 }
 window.getAmixBlackHolePoint=()=>point();
 function resize(){
  w=Math.max(1,window.innerWidth);h=Math.max(1,window.innerHeight);dpr=Math.min(window.devicePixelRatio||1,1.5);
  canvas.width=Math.round(w*dpr);canvas.height=Math.round(h*dpr);canvas.style.width=w+'px';canvas.style.height=h+'px';
  ctx.setTransform(dpr,0,0,dpr,0,0);
  stars=Array.from({length:Math.min(200,Math.round(w*h/6000))},()=>({x:Math.random()*w,y:Math.random()*h,r:rand(.25,1.15),phase:rand(0,Math.PI*2),tw:rand(.25,1.2)}));
  plasma=Array.from({length:124},()=>({a:rand(0,Math.PI*2),orbit:rand(1.08,2.08),speed:rand(.22,.64)*(Math.random()<.5?-1:1),size:rand(.45,1.6),phase:rand(0,7)}));
  draw(0,true);
 }
 function ellipseStroke(x,y,rx,ry,rotation,color,width,blur=0){ctx.save();ctx.translate(x,y);ctx.rotate(rotation);ctx.beginPath();ctx.ellipse(0,0,rx,ry,0,0,Math.PI*2);ctx.strokeStyle=color;ctx.lineWidth=width;ctx.shadowColor=color;ctx.shadowBlur=blur;ctx.stroke();ctx.restore()}
 function softRing(d,a0,a1,mult){const N=Math.max(8,Math.round(72*(a1-a0)/(Math.PI*2)));ctx.lineCap='round';ctx.lineWidth=d.w;ctx.shadowBlur=0;for(let k=0;k<N;k++){const st=a0+(a1-a0)*k/N,en=a0+(a1-a0)*Math.min(1,(k+1.5)/N),m=(st+en)/2;const al=(.18+.82*Math.pow(.5+.5*Math.cos(m-d.phase),2.2))*.62*mult;ctx.beginPath();ctx.ellipse(0,0,d.rx,d.ry,0,st,en);ctx.strokeStyle='rgba('+d.rgb+','+al.toFixed(3)+')';ctx.stroke()}}
 function taperArc(x,y,rx,ry,a0,a1,rgb,alpha,width){const N=48;ctx.lineCap='round';ctx.lineWidth=width;ctx.shadowBlur=0;for(let k=0;k<N;k++){const st=k/N,en=Math.min(1,(k+1.5)/N),m=(st+en)/2;const taper=Math.pow(Math.sin(m*Math.PI),.75);ctx.beginPath();ctx.ellipse(x,y,rx,ry,0,a0+(a1-a0)*st,a0+(a1-a0)*en);ctx.strokeStyle='rgba('+rgb+','+(alpha*taper).toFixed(3)+')';ctx.stroke()}}
 function draw(now,staticFrame=false){
  if(!staticFrame && document.hidden){frame=requestAnimationFrame(draw);return;}
  const dt=last?Math.min(.04,Math.max(0,(now-last)/1000)):0;last=now;if(!reduce)t+=dt;
  ctx.clearRect(0,0,w,h);

  for(const s of stars){
   const alpha=.10+.24*(.5+.5*Math.sin(t*s.tw+s.phase));
   ctx.globalAlpha=alpha;
   ctx.fillStyle=s.phase>3?'#d8e6ff':'#f6f0e9';
   ctx.beginPath();ctx.arc(s.x,s.y,s.r,0,Math.PI*2);ctx.fill();
  }
  ctx.globalAlpha=1;

  const wantsVoice=document.body.classList.contains('voice-mode');
  if(wantsVoice){
   if(voiceDelay<700) voiceDelay+=dt*1000;
  }else{
   voiceDelay=0;
  }
  const target=(wantsVoice && voiceDelay>=700)?1:0;
  voiceExpansion += (target-voiceExpansion)*.035;
  if(voiceExpansion<.005) voiceExpansion=0;
  if(voiceExpansion>.995) voiceExpansion=1;

  if(voiceExpansion<.015){
   const vg=ctx.createRadialGradient(w*.5,h*.48,Math.min(w,h)*.10,w*.5,h*.48,Math.max(w,h)*.80);
   vg.addColorStop(0,'rgba(0,0,0,0)');vg.addColorStop(1,'rgba(0,0,0,.55)');
   ctx.fillStyle=vg;ctx.fillRect(0,0,w,h);
   if(!reduce && !staticFrame) frame=requestAnimationFrame(draw);
   return;
  }

  const c=point();
  const voiceLevel=Math.min(1,Math.max(0,Number(window.amixVoiceLevel)||0));
  const wobble=voiceExpansion*(.25+voiceLevel*5.8);
  const cx=c.x+Math.sin(t*63)*wobble,cy=c.y+Math.cos(t*51)*wobble*.72;
  const normalR=Math.max(49,Math.min(119,Math.min(w,h)*.13));
  const giantR=Math.min(w,h)*.465;
  const voiceBreath=1+voiceExpansion*(.008*Math.sin(t*2.4))+voiceLevel*(.028+.018*Math.sin(t*70));
  const rRaw=(normalR+(giantR-normalR)*voiceExpansion)*voiceBreath;
  const fitR=(w*.5)/2.2;
  const r=rRaw+(Math.min(rRaw,fitR)-rRaw)*voiceExpansion;
  const a=voiceExpansion;
  const diskRX=r*2.55,diskRY=r*.79,spin=t*(.16+voiceExpansion*.58+voiceLevel*1.55);
  let halo=ctx.createRadialGradient(cx,cy,r*.35,cx,cy,r*3.05);
  halo.addColorStop(0,'rgba(0,0,0,0)');halo.addColorStop(.25,'rgba(255,158,86,'+(.045*a).toFixed(3)+')');halo.addColorStop(.43,'rgba(235,105,43,'+(.10*a).toFixed(3)+')');halo.addColorStop(.61,'rgba(112,95,180,'+(.055*a).toFixed(3)+')');halo.addColorStop(1,'rgba(0,0,0,0)');
  ctx.fillStyle=halo;ctx.beginPath();ctx.ellipse(cx,cy,diskRX*1.45,diskRY*2.25,-.045,0,Math.PI*2);ctx.fill();
  ctx.save();ctx.translate(cx,cy);ctx.rotate(-.055+Math.sin(t*.13)*.012);ctx.scale(1,.31);
  let disk=ctx.createRadialGradient(0,0,r*.48,0,0,r*2.7);
  disk.addColorStop(0,'rgba(0,0,0,0)');disk.addColorStop(.24,'rgba(29,18,19,'+(.12*a).toFixed(3)+')');disk.addColorStop(.40,'rgba(123,47,32,'+(.43*a).toFixed(3)+')');disk.addColorStop(.54,'rgba(244,119,54,'+(.88*a).toFixed(3)+')');disk.addColorStop(.60,'rgba(255,225,169,'+(.95*a).toFixed(3)+')');disk.addColorStop(.66,'rgba(255,137,61,'+(.93*a).toFixed(3)+')');disk.addColorStop(.77,'rgba(184,60,37,'+(.68*a).toFixed(3)+')');disk.addColorStop(.91,'rgba(73,38,49,'+(.18*a).toFixed(3)+')');disk.addColorStop(1,'rgba(0,0,0,0)');
  ctx.fillStyle=disk;ctx.beginPath();ctx.ellipse(0,0,r*2.7,r*2.7,0,0,Math.PI*2);ctx.fill();
  const rings=[];
  for(let i=0;i<5;i++){const rr=r*(1.05+i*.28),phase=spin*(i%2?1:-1)+i*.9;const def={rx:rr*1.45,ry:rr*.88,rgb:i%2?'255,201,141':'255,106,47',w:Math.max(.9,2.3-i*.28),phase};rings.push(def);softRing(def,0,Math.PI*2,a)}
  ctx.restore();
  const drawPlasma=(front)=>{for(const p of plasma){const aa=p.a+t*p.speed*(1+voiceExpansion*1.6+voiceLevel*2.8);if((Math.sin(aa)>0)!==front)continue;const rr=r*p.orbit;const px=cx+Math.cos(aa)*rr*1.54,py=cy+Math.sin(aa)*rr*.34;const tailA=aa-p.speed*.10;const tx=cx+Math.cos(tailA)*rr*1.54,ty=cy+Math.sin(tailA)*rr*.34;const hot=.45+.55*(.5+.5*Math.sin(aa*2+p.phase));ctx.globalAlpha=(.2+.62*hot)*a;ctx.strokeStyle=hot>.77?'#fff0cc':hot>.48?'#ffb46f':'#dd7046';ctx.lineWidth=p.size;ctx.shadowColor='#ff8b45';ctx.shadowBlur=4+hot*5;ctx.beginPath();ctx.moveTo(tx,ty);ctx.lineTo(px,py);ctx.stroke();ctx.globalAlpha=1}ctx.shadowBlur=0};
  drawPlasma(false);ctx.shadowBlur=0;
  const holeR=r*.68;
  let shadow=ctx.createRadialGradient(cx-holeR*.2,cy-holeR*.25,holeR*.15,cx,cy,holeR*1.27);
  shadow.addColorStop(0,'rgba(0,0,0,'+a.toFixed(3)+')');shadow.addColorStop(.72,'rgba(0,0,0,'+a.toFixed(3)+')');shadow.addColorStop(.87,'rgba(3,3,3,'+a.toFixed(3)+')');shadow.addColorStop(.96,'rgba(255,213,168,'+(.2*a).toFixed(3)+')');shadow.addColorStop(1,'rgba(255,148,83,0)');
  ctx.fillStyle=shadow;ctx.beginPath();ctx.arc(cx,cy,holeR*1.27,0,Math.PI*2);ctx.fill();
  ellipseStroke(cx,cy,holeR*1.02,holeR*.99,0,'rgba(255,239,215,'+(.78*a).toFixed(3)+')',Math.max(1.1,r*.017),12);
  ellipseStroke(cx,cy,holeR*1.10,holeR*1.055,0,'rgba(255,136,72,'+(.65*a).toFixed(3)+')',Math.max(.8,r*.012),18);
  ctx.save();ctx.translate(cx,cy);ctx.rotate(-.035);
  const lw=Math.max(1.1,r*.018);
  taperArc(0,-holeR*.03,holeR*1.23,holeR*1.15,Math.PI,Math.PI*2,'255,160,95',.2*a,lw*3.6);
  taperArc(0,-holeR*.03,holeR*1.23,holeR*1.15,Math.PI,Math.PI*2,'255,236,208',.8*a,lw);
  taperArc(0,holeR*.05,holeR*1.20,holeR*1.12,0,Math.PI,'255,110,55',.18*a,lw*3.2);
  taperArc(0,holeR*.05,holeR*1.20,holeR*1.12,0,Math.PI,'255,128,67',.7*a,lw*.8);
  ctx.restore();
  const core=ctx.createRadialGradient(cx,cy,holeR*.15,cx,cy,holeR*1.01);
  core.addColorStop(0,'rgba(0,0,0,'+a.toFixed(3)+')');core.addColorStop(.90,'rgba(0,0,0,'+a.toFixed(3)+')');core.addColorStop(.985,'rgba(3,3,3,'+a.toFixed(3)+')');core.addColorStop(1,'rgba(0,0,0,0)');
  ctx.fillStyle=core;ctx.beginPath();ctx.arc(cx,cy,holeR*1.01,0,Math.PI*2);ctx.fill();
  ctx.save();ctx.translate(cx,cy);ctx.rotate(-.055+Math.sin(t*.13)*.012);ctx.scale(1,.31);
  for(const d of rings)softRing(d,0,Math.PI,.85*a);
  ctx.restore();
  drawPlasma(true);ctx.shadowBlur=0;
  const vg=ctx.createRadialGradient(w*.5,h*.48,Math.min(w,h)*.12,w*.5,h*.48,Math.max(w,h)*.78);
  vg.addColorStop(0,'rgba(0,0,0,0)');vg.addColorStop(1,'rgba(0,0,0,'+(.44+.14*(1-a)).toFixed(3)+')');ctx.fillStyle=vg;ctx.fillRect(0,0,w,h);
  if(!reduce && !staticFrame)frame=requestAnimationFrame(draw);
 }
 resize();
 window.addEventListener('resize',resize,{passive:true});
 document.addEventListener('visibilitychange',()=>{if(!document.hidden&&!reduce){last=0;cancelAnimationFrame(frame);frame=requestAnimationFrame(draw)}});
 if(!reduce)frame=requestAnimationFrame(draw);
})();
