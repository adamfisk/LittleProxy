// '''WikiMiniAtlas''' 
// Script to embed interactive maps into pages that have coordinate templates
// also check my user page [[User:Dschwen]] for more tools
//
// Revision 16.9
jQuery(function ($) {
 var config = {
  width  : 600,
  height : 400,
  timeout : 5000,
  zoom : -1,
  enabled : true,
  onlytitle : false,
  flowTextTooltips: (location.host === "en.wikipedia.org"),
  alwaysTooltips: false,
  iframeurl : '//toolserver.org/~dschwen/wma/iframe.html',
  imgbase   : '//toolserver.org/~dschwen/wma/tiles/',
  buttonImage: '//upload.wikimedia.org/wikipedia/commons/thumb/5/55/WMA_button2b.png/17px-WMA_button2b.png'
 },
 strings = {
  buttonTooltip : {
   af:'Vertoon ligging op \'n interaktiwe kaart.',
   als:'Ort uf dr interaktivä Chartä zeigä',
   ar:'شاهد الموقع على الخريطة التفاعلية',
   ast:'Ver el llugar nun mapa interactivu',
   'be-tarask':'паказаць месцазнаходжаньне на інтэрактыўнай мапе',
   'be-x-old':'паказаць месцазнаходжаньне на інтэрактыўнай мапе',
   bar:'Ort af da interaktivn Kartn zoagn',
   bg:'покажи местоположението на интерактивната карта',
   bpy:'জীবন্ত মানচিত্রগর মা মাপাহান দেখাদিতই',
   br:'diskouez al lec\'hiadur war ur gartenn etrewezhiat',
   ca:'mostra la localització en un mapa interactiu',
   cs:'zobraz místo na interaktivní mapě',
   da:'vis beliggenhed på interaktivt kort',
   de:'Ort auf interaktiver Karte anzeigen',
   dsb:'Městno na interaktiwnej kórśe zwobrazniś',
   fa:'نمایش مکان در نقشه‌ای پویا',
   el:'εμφάνιση τοποθεσίας σε διαδραστικό χάρτη',
   en:'Show location on an interactive map',
   bn:'সক্রিয় মানচিত্রে অবস্থান চিহ্নিত করুন',
   eo:'Montru lokigon sur interaktiva karto',
   eu:'erakutsi kokalekua mapa interaktibo batean',
   es:'Mostrar el lugar en un mapa interactivo',
   fr:'Montrer la localisation sur une carte interactive',
   fur:'mostre la localizazion suntune mape interative',
   fy:'it plak op in oanpasbere kaart oanjaan',
   gl:'Amosar o lugar nun mapa interactivo',
   he:'הראה מיקום במפה האינטראקטיבית',
   hi:'सक्रिय नक्शे पर लोकेशन या स्थान दिखायें', 
   hr:'prikaži lokaciju na interaktivnom zemljovidu',
   hsb:'Městno na interaktiwnej karće zwobraznić',
   hu:'Mutasd a helyet egy interaktív térképen!',
   hy:'ցուցադրել դիրքը ինտերակտիվ քարտեզի վրա',
   id:'Tunjukkan lokasi di peta interaktif',
   ilo:'Ipakita ti lokasion iti interaktibo a mapa',
   is:'sýna staðsetningu á gagnvirku korti',
   it:'mostra la località su una carta interattiva',
   ja:'インタラクティブ地図上に位置を表示',
   kk:'интерактивті картадан орналасуын көрсету',
   km:'បង្ហាញទីតាំងនៅលើផែនទីអន្តរកម្ម',
   ko:'인터랙티브 지도에 위치를 표시',
   lt:'Rodyti vietą interaktyviame žemėlapyje',
   lv:'Rādīt atrašanās vietu interaktīvajā kartē',
   min:'Tunjuakan lokasi pado peta',
   mk:'прикажи положба на интерактивна карта',
   ms:'Tunjukkan lokasi pada peta interaktif',
   nl:'de locatie op een interactieve kaart tonen',
   no:'vis beliggenhet på interaktivt kart',
   nv:'kéyah tʼáá dah siʼą́ą́ ńtʼę́ę́ʼ beʼelyaaígíí',
   pl:'Pokaż lokalizację na mapie interaktywnej',
   pt:'mostrar a localidade num mapa interactivo',
   ro:'Arată locaţia pe o hartă interactivă',
   ru:'показать положение на интерактивной карте',
   sk:'zobraz miesto na interaktívnej mape',
   sl:'Prikaži lego na interaktivnem zemljevidu',
   sr:'Прикажи локацију на интерактивној мапи',
   sq:'trego vendndodhjen në hartë',
   fi:'näytä paikka interaktiivisella kartalla',
   sv:'visa platsen på en interaktiv karta',
   tr:'Yeri interaktif bir haritada göster',
   uk:'показати положення на інтерактивній карті',
   vi:'xem vị trí này trên bản đồ tương tác',
   vo:'Jonön topi su kaed itjäfidik',
   zh:'显示该地在地图上的位置',
   'zh-cn':'显示该地在地图上的位置',
   'zh-sg':'显示该地在地图上的位置',
   'zh-tw':'顯示該地在地圖上的位置',
   'zh-hk':'顯示該地在地圖上的位置'
  },
  map: {
   ast:'Mapa',
   de:'Karte',
   en:'Map',
   es:'Mapa',
   fa:'نقشه',
   fi:'Kartalla',
   fr:'Carte',
   gl:'Mapa',
   id:'peta',
   ilo:'Mapa',
   ja:'地図',
   min:'peta',
   mk:'карта',
   ms:'Peta',
   nl:'Kaart',
   pt: 'Mapa',
   ru:'карте'
  },
  close : {
   af:'Sluit',
   als:'Zuä machä',
   ar:'غلق',
   ast:'zarrar',
   'be-tarask':'закрыць',
   'be-x-old':'закрыць',
   bar:'zuamachn',
   bg:'затвори',
   bpy:'জিপা',
   br:'serriñ',
   ca:'tanca',
   cs:'zavřít',
   da:'luk',
   de:'schließen',
   dsb:'zacyniś',
   nv:'doo yishʼį́ nisin da',
   el:'έξοδος',
   en:'close',
   bn:'বন্ধ করুন',
   eo:'fermu', 
   eu:'itxi',
   es:'cerrar',
   fa:'بستن',
   fr:'Quitter',
   fur:'siere',
   fy:'ticht',
   gl:'pechar',
   he:'לסגור',
   hi:'बंद करें',
   hr:'zatvori',
   hsb:'začinić',
   hu:'bezárás', 
   hy:'փակել',
   id:'tutup',
   ilo:'irikep',
   is:'loka',
   it:'chiudi',
   ja:'閉じる',
   kk:'жабу',
   km:'បិទ',
   ko:'닫기',
   lt:'uždaryti',
   lv:'aizvērt',
   min:'tutuik',
   mk:'затвори',
   ms:'tutup',
   nl:'sluiten',
   no:'lukk',
   pl:'zamknij',
   pt:'fechar',
   ro:'închide',
   ru:'закрыть',
   sk:'zatvoriť',
   sl:'zapri',
   sr:'затвори',
   sq:'mbylle',
   fi:'sulje',
   sv:'stäng',
   tr:'kapat',
   uk:'закрити',
   vi:'đóng',
   vo:'färmükön',
   zh:'关闭',
   'zh-cn':'关闭',
   'zh-sg':'关闭',
   'zh-tw':'關閉',
   'zh-hk':'關閉'
  },
  resize : {
   ar: 'تغيير حجم',
   ast: 'redimensionar',
   ca: 'redimensionar',
   de: 'Größe ändern',
   dk: 'ændre størrelse',
   en: 'resize',
   es: 'cambiar el tamaño',
   fa: 'تغییر اندازه',
   fi: 'muuta kokoa',
   fr: 'redimensionner',
   gl: 'cambiar o tamaño',
   ilo: 'baliwan ti kadakkel',
   ja: 'サイズを変更する',
   kk: 'өлшемін өзгерту',
   min: 'gadangan',
   mk: 'промени големина',
   ms: 'ubah saiz',
   nl: 'vergroten of verkleinen',
   no: 'endre størrelse',
   pt: 'alterar tamanho',
   ro: 'redimensionare',
   sr: 'промени величину',
   sv: 'ändra storlek',
   zh: '调整大小',
   'zh-cn': '调整大小'
  }
 },

 // Get a specific, localized string
 _msg = function(k) {
  return strings[k][language] || strings[k].en
 },
 dbName = mw.config.get( 'wgDBname' ),
 
 language = '', site = '', awt="0", rtl = /(^|\s)rtl(\s|$)/.test(document.body.className),
 iframe = { div: null, iframe: null, closebutton: null, resizebutton: null, resizehelper: null, indom: false },
 
 page_title = (mw.config.get('wgNamespaceNumber')==0) ? encodeURIComponent(mw.config.get('wgTitle')) : '',

 bodyc,
 coord_filter = /&params=([\d.+-]+)_([\d.+-]*)_?([\d.+-]*)_?([NSZ])_([\d.+-]+)_([\d.+-]*)_?([\d.+-]*)_?([EOW])([^&=<>|]{0,250})/,
 coord_list = [],
 coord_highlight = -1,

 kml = null,
 mes = null;

 // get position on page
 function yPos(el) {
  return $(el).offset().top + $(el).outerHeight();
 }

 // show, move, and update iframe
 function showIFrame(e) {
  var wi = iframe, my = yPos(this),
      newurl = config.iframeurl + '?wma=' + e.data.param + '&lang=' + site + '&page=' + page_title + '&awt=' + awt;

  // insert iframe into DOM on demand (to preserve page caching)
  if( !wi.indom ) {
   $('#content,#mw_content').prepend(wi.div);
   wi.indom = true;
  }

  if( wi.iframe.attr('src') !== newurl ) {
   wi.iframe.attr( 'src', newurl );
  } else if( wi.div.css('display') !== 'none' ) {
   wi.div.hide();
   return false;
  }
  wi.div.css( 'top', my+'px' ).show();
  return false;
 }

 function highlight(i) {
  if( coord_highlight >= 0 ) {
   $(coord_list[coord_highlight].obj).css('background-color','').find('span:visible').css('background-color','');
  }
  coord_highlight = i;
  if( coord_highlight >= 0 ) {
   $(coord_list[coord_highlight].obj).css('background-color','yellow').find('span:visible').css('background-color','yellow');
  }
 }

 function messageHub(e) {
  var i, d, clist = { coords: [] }
    , geoext = [], sx=0, sy=0, s
    , minlat = Infinity, maxlat = -Infinity, ineg = -1, ipos = -1;
  e = e.originalEvent;
  d = e.data.split(',');
  mes = e.source;
  switch(d[0]) { 
   case 'request' :
    // make a JSON encodable copy of coord_list (no HTML objects!)
    // find center and extent
    for( i = 0; i < coord_list.length; ++i ) {
     clist.coords[i] = {
      lat: coord_list[i].lat,
      lon: coord_list[i].lon,
      title: coord_list[i].title.replace(/[\+_]/g,' ')
     };
     if(  coord_list[i].lat < minlat ) { minlat = coord_list[i].lat; }
     if(  coord_list[i].lat > maxlat ) { maxlat = coord_list[i].lat; }
     geoext[i] = {
      x: Math.cos(coord_list[i].lon/180.0*Math.PI),
      y: Math.sin(coord_list[i].lon/180.0*Math.PI)
     }
     sx += geoext[i].x;
     sy += geoext[i].y;
    }
    clist.loncenter = Math.atan2(sy,sx)*180.0/Math.PI;
    clist.latmax = maxlat;
    clist.latmin = minlat;
    // extent in longitude
    for( i = 0; i < geoext.length; ++i ) {
     s = (geoext[i].x*sy-geoext[i].y*sx);
     geoext[i].z = (geoext[i].x*sx+geoext[i].y*sy);
     if( s<0 && ( ineg<0 || geoext[i].z<geoext[ineg].z ) ) { ineg=i; }
     if( s>0 && ( ipos<0 || geoext[i].z<geoext[ipos].z ) ) { ipos=i; }
    }
    if( ipos>=0 && ineg>=0 ) {
     clist.lonleft  = coord_list[ipos].lon;
     clist.lonright = coord_list[ineg].lon;
    }
    if( typeof JSON !== "undefined" ) {
     mes.postMessage( JSON.stringify(clist), document.location.protocol + '//toolserver.org' );
     if( kml !== null ) {
      mes.postMessage( JSON.stringify(kml), document.location.protocol + '//toolserver.org' );
     }
    }
   case 'unhighlight' :
    highlight(-1);   
    break;
   case 'toggle' : 
    coord_list[parseInt(d[1])].mb.click();
    break;
   case 'scroll' :
    $("html:not(:animated),body:not(:animated)").animate({ scrollTop: $(coord_list[parseInt(d[1])].obj).offset().top - 20 + parseInt(d[2]||0) }, 500 );
    iframe.div.css( { top: yPos( coord_list[parseInt(d[1])].obj ) + 'px'} );
    // make sure scroll target gets highlighted
    setTimeout( function () { highlight(parseInt(d[1])); }, 200 );
    break;
   case 'highlight' :
    highlight(parseInt(d[1]));
    break;
  }  
 }

 // parse url parameters into a hash
 function parseParams(url) {
  var map = {}, h, i, pair = url.substr(url.indexOf('?')+1).split('&');
  for( i=0; i<pair.length; ++i ) {
   h = pair[i].split('=');
   map[h[0]] = h[1];
  }
  return map;
 }

 // Insert the IFrame into the page.

 var wi = iframe,
     wc = config,
     marker = { lat:0, lon:0 }, coordinates = null,
     link, links, key, len, coord_title, icons, startTime, content, mapbutton;

 // apply settings
 if( typeof(wma_settings) === 'object' ) {
  for( key in wma_settings ) {
   if( typeof(wma_settings[key]) === typeof(wc[key]) ) {
    wc[key] = wma_settings[key];
   }
  }
 }

 if( wc.enabled === false ) { return; }

 site = ( dbName == "commonswiki" ) ? "commons" : mw.config.get( 'wgPageContentLanguage' );
 language = mw.config.get( 'wgUserLanguage' );

 // remove icons from title coordinates
 $('#coordinates,#coordinates-title,#tpl_Coordinaten').find('a.image').detach();
 
 bodyc = $( wc.onlytitle ? '#coordinates,#coordinates-title' : 'html' );
 startTime = (new Date()).getTime();

 bodyc.find('a.external.text').each( function( key, link ) {
  var ws, coord_params, params, zoomlevel, globe="Earth";

  // check for timeout (every 10 links only)
  if( key % 10 === 9 && (new Date()).getTime() > startTime + wc.timeout ) { 
   return false; // break out of each
  }

  if( !('href' in link) || !coord_filter.exec(link.href) ){ // invalid links do not contain href attribute in IE!
   return true;
  }
  marker.lat=(1.0*RegExp.$1) + ((RegExp.$2||0)/60.0) + ((RegExp.$3||0)/3600.0);
  if( RegExp.$4 !== 'N' ) { marker.lat*=-1; }
  marker.lon=(1.0*RegExp.$5) + ((RegExp.$6||0)/60.0) + ((RegExp.$7||0)/3600.0);
  if( RegExp.$8 === 'W' ) { marker.lon*=-1; }
  coord_params = RegExp.$9;

  // Zoom based on coordinate N/S precision 
  var coord_digits = RegExp.$3 ? 4 : RegExp.$2 ? 2 : RegExp.$1.length - (RegExp.$1+".").indexOf('.') - 1;
  zoomlevel = coord_digits * Math.log(10)/Math.log(2);

  // Find a sensible Zoom-level based on type
  if( /_type:(airport|edu|pass|landmark|railwaystation)/.test(coord_params) ) {
   zoomlevel = 8;
  } else if( /_type:(event|forest|glacier)/.test(coord_params) ) {
   zoomlevel = 6;
  } else if( /_type:(adm3rd|city|mountain|isle|river|waterbody)/.test(coord_params) ) {
   zoomlevel = 4;
  }

  // wma shows dim approx 4e7m at zoom 0 or 1.5e8 is the scale of zoomlevel 0
  if( /_dim:([\d.+-]+)(km|m|_|$)/.exec(coord_params) ) {
   zoomlevel = Math.log( ( RegExp.$2 === "km" ? 4e4 : 4e7 ) / RegExp.$1 ) / Math.log(2);
  }
  if( /_scale:(\d+)(_|$)/.exec(coord_params) ) {
   zoomlevel = Math.log( 1.5e8/RegExp.$1 ) / Math.log(2);
  }

  if( wc.zoom !== -1 ) { zoomlevel = wc.zoom; }
  //if( zoomlevel > 12 ) { zoomlevel = 12; }
  if( zoomlevel < 0 ) { zoomlevel = 0; }

  function capitalize(s) { return s.substr(0,1).toUpperCase()+s.substr(1).toLowerCase(); }
  if( /_globe:([^_&]+)/.test(coord_params) ) { globe = capitalize(RegExp.$1); }
  if( $.inArray(globe,["Earth","Moon","Mars","Venus","Mercury","Io","Titan"]) < 0 ) { return; }

  // Test the unicode Symbol
  if( site === 'de' && link.parentNode.id !== 'coordinates' ) {
   mapbutton = $('<span>♁</span>').css('color','blue');
  } else {
   mapbutton = $('<img>').attr('src', wc.buttonImage);
  }
  mapbutton.addClass('wmamapbutton').attr( {
   title: _msg('buttonTooltip'),
   alt: '' 
  } )
  .hover(function (){ $(this).css('opacity', 0.75); }, function () { $(this).css('opacity', ''); })
  .addClass('noprint')
  .css('padding', rtl ? '0px 0px 0px 3px' : '0px 3px 0px 0px' ).css('cursor', 'pointer');

  if( wc.alwaysTooltips || ( wc.flowTextTooltips && $(link).parents('li, table, #coordinates').length == 0 ) ) {
   // insert tooltip rather than icon to improve text readability
   mapbutton = $('<span>').append(mapbutton).append("&nbsp;WikiMiniAtlas").css('cursor','pointer');
   var tooltip = $('<div>').css( {
    backgroundColor: 'white', padding: '0.2em', border: '1px solid black',
    position: 'absolute', top: '1em', left: '0em', 
    display: 'none', zIndex : 15
   }).append(mapbutton);
   $(link).wrap( 
    $('<span/>')
     .css( { position: 'relative', whiteSpace: 'nowrap' } )
     .mouseleave( function () { tooltip.fadeOut() } ) 
    )
    .before( tooltip )
    .mouseenter( function () { tooltip.fadeIn() } );
  } else {
   // insert icon directly
   ws = $(link).css('whiteSpace');
   if( site !== 'de' || link.parentNode.id !== 'coordinates' ) {
    $(link).wrap( $('<span/>').css('whiteSpace', 'nowrap') ).css('whiteSpace', ws).before(mapbutton);
   } else {
    $('#coordinates').append('<span class="noprint coordinates-separator"> | </span>').append(mapbutton);
   }
  }

  mapbutton.bind( 'click', { param:
   marker.lat + '_' + marker.lon + '_' +
   wc.width + '_' + wc.height + '_' +
   site + '_' + zoomlevel + '_' + language + '&globe=' + globe }, showIFrame );

  // store coordinates
  coordinates = link.href;
  params = parseParams(link.href);
  coord_list.push( { lat: marker.lat, lon: marker.lon, obj: link, mb: mapbutton, title: params.title || params.pagename || '' } );
 } ); //end each

 var titlebutton = false;

 function addTitleButton( alat, alon, zoomlevel ) {
  mapbutton = $('<img>')
   .hover(function (){ $(this).css('opacity', 0.75); }, function () { $(this).css('opacity', ''); })
   .css('padding', rtl ? '0px 3px 0px 0px' : '0px 0px 0px 3px' ).css('cursor', 'pointer')       
   .attr('src', wc.buttonImage).addClass('wmamapbutton').addClass('noprint')
   .bind( 'click', { param:
    alat + '_' + alon + '_' +
    wc.width + '_' + wc.height + '_' +
    site + '_' + zoomlevel + '_' + language 
   }, showIFrame ); // zoomlevel!

  if(!titlebutton ) { 
   if( $('#coordinates').length ) {
    $('#coordinates').find('img').detach();
    $('#coordinates').append(mapbutton);
   } else {
    $('<span id="coordinates"></span>').text(_msg('map')).append(mapbutton).appendTo('#bodyContent');
   }
   titlebutton = true;
  }
 }

 // detect and load KML
 // also insert globe even if no title coords are given
 (function () {
  var i, l = $('div.kmldata')
     ,alat = 0, alon = 0, np = 0
     ,la1 = Infinity, la2 =- Infinity
     ,lo1 = Infinity, lo2 =- Infinity
     ,ex,ey;
  for( i = 0; i < l.length; ++i ) {// TODO: replace with .each
   coordinates = true;
   $.ajax({
    url: '/wiki/' + encodeURI(l.eq(i).attr('title')) + '?action=raw',
    dataType: 'xml',
    success: function (xml) {
     function processCoords(t) {
      var way = [], c, p = t.split(' '), i, lat, lon;
      for( i=0; i<p.length; ++i ) {
       c=p[i].split(',');
       if( c.length >= 2 ) {
        lat = parseFloat(c[1]);
        lon = parseFloat(c[0]);
        way.push( { lat: lat, lon: lon } );

        // determine extent of way
        if( lat<la1 ) { la1=lat; }
        if( lon<lo1 ) { lo1=lon; }
        if( lat>la2 ) { la2=lat; }
        if( lon>lo2 ) { lo2=lon; }
       }
      }
      return way;
     }

     // initialize transfer datastructure
     kml = { ways: [], areas: [] };

     // ways
     $(xml).find('LineString > coordinates').each(function () {
      var way = processCoords( $(this).text() );
      if( way.length > 0 ) { kml.ways.push(way); }
     });

     // areas
     $(xml).find('Polygon').each(function () {
      var i, j, c,
       area = { inner: [], outer: [] };

      // outer boundary
      $(this).find('outerBoundaryIs > LinearRing > coordinates').each(function () {
       var way = processCoords( $(this).text() );
       if( way.length > 0 ) {
        area.outer.push(way);
       }
      });

      // inner boundary (holes in the polygon)
      $(this).find('innerBoundaryIs > LinearRing > coordinates').each(function () {
       var way = processCoords( $(this).text() );
       if( way.length > 0 ) { area.inner.push(way); }
      });

      // only add if we have an outer boundary
      if( area.outer.length>0 ) { kml.areas.push(area); }
     });

     // inset min/max extent
     kml.minlon = lo1;
     kml.maxlon = lo2;
     kml.minlat = la1;
     kml.maxlat = la2;

     // already got a request message
     if( mes !== null && kml.ways.length > 0 && typeof JSON !== "undefined" ) {
      mes.postMessage( JSON.stringify(kml), document.location.protocol + '//toolserver.org' );
     }

     // insert blue globe
     if( coord_list.length == 0 || ( !$('#coordinates').find('.wmamapbutton').length) ) {
      // determine center
      alat = (la1+la2)/2;
      alon = (lo1+lo2)/2;

      //determine zoomfactor
      ex = (lo2-lo1)/180.0 * 3.0*128;
      ey = (la2-la1)/180.0 * 3.0*128; // max extent in degrees, zoom0 has 3*128/180 px/degree
      for( zoomlevel = 0; zoomlevel < 12; ++zoomlevel ) {
       if( ex>config.width/2 || ey>config.height/2 ) break;
       ex *= 2; ey *= 2;
      }

      // add mapbutton
      addTitleButton( alat, alon, zoomlevel );
     }
    }
   });
  } // end for
 })();

 // detect "All Coordinates"
 links = $('#coordinates>span>a');
 if( links.length>0 && links[0].href.substr(0,50) == "http://www.lenz-online.de/cgi-bin/wiki/wiki-osm.pl" ) {
   addTitleButton( 0, 0, 1 );
   coordinates = true;
 }

 // prepare iframe to house the map
 if ( coordinates !== null ) {
  wi.div = $('<div/>').css( {
   width: (wc.width+2)+'px', height: (wc.height+2)+'px',
   margin: '0px', padding: '0px', 
   backgroundColor : 'white', border: '1px solid gray',
   position: 'absolute', top: '1em', zIndex: 13, boxShadow: '3px 3px 25px rgba(0,0,0,0.3)'
  } ).css( rtl ? 'left' : 'right', '2em' ).hide();

  var rbrtl = [ '//upload.wikimedia.org/wikipedia/commons/b/b5/Button_resize.png',
                '//upload.wikimedia.org/wikipedia/commons/3/30/Button_resize_rtl.png' ]
  wi.resizebutton = $('<img>').attr( { 
   title : _msg('resize'),
   src : rbrtl[rtl?1:0]
  } ).hide().attr('ondragstart','return false');
  
  // cover the iframe to prevent loosing the mouse to the iframe during resizing
  wi.resizehelper = $('<div/>').css( { position: 'absolute', top:0, left:0, zIndex: 20 } ).hide();

  wi.closebutton = $('<img>').attr( { 
   title : _msg('close'),
   src : '//upload.wikimedia.org/wikipedia/commons/d/d4/Button_hide.png'
  } ).css( {
   zIndex : 15, position : 'absolute', right : '11px', top : '9px', width : '18px', cursor : 'pointer'
  } ).click( function(e) { wi.div.hide() } );

  wi.iframe = $('<iframe/>')
   .attr( { scrolling: 'no', frameBorder : 0 } )
   .css( {
    zIndex: 14, position: 'absolute', right: '1px', top: '1px',
    width: (wc.width)+'px', height: (wc.height)+'px',
    margin: '0px', padding: '0px'
   } );

  wi.div.append(wi.iframe);
  wi.div.append(wi.resizehelper);
  wi.div.append(wi.closebutton);
  (function () {
   var startx, starty, idle = true, dir = rtl?-1:1;
   function adjusthelper() {
    wi.resizehelper.css( { width: (wc.width+2)+'px', height: (wc.height+2)+'px' } );
   }
   wi.div.append(  
    $('<div/>')
     .css( {
       zIndex : 15, position : 'absolute', bottom : '3px', 
       width : '18px', height: '18px', cursor : (rtl?'se-resize':'sw-resize'),
       'user-select': 'none', '-moz-user-select': 'none', '-ms-user-select': 'none'
      } ).css( (rtl?'right':'left'), '3px' )
     .mouseenter( function(e) { wi.resizebutton.fadeIn() } )
     .mouseleave( function(e) { if( idle ) { wi.resizebutton.fadeOut(); } } )
     .mousedown( function(e) {
       if( idle ) { 
        wi.resizehelper.show();
        adjusthelper();
        lastx = e.pageX;
        lasty = e.pageY;
        $('body').bind('mouseup.wmaresize', function(e) { 
         $('body').unbind('mousemove.wmaresize');  
         $('body').unbind('mouseup.wmaresize'); 
         idle = true;
         wi.resizehelper.hide();
        } );
        $('body').bind('mousemove.wmaresize', function(e) { 
         wc.width -= dir*(e.pageX-lastx);
         wc.height += (e.pageY-lasty);
         lastx = e.pageX; lasty = e.pageY;
         wi.div.css( { width: (wc.width+2)+'px', height: (wc.height+2)+'px' } );
         wi.iframe.css( { width: wc.width+'px', height: wc.height+'px' } );
         adjusthelper();
        } );
        idle = false;
       }
      } )
     .append(wi.resizebutton) 
   );
  })();

  $(window).bind('message', messageHub);
 }
});

// </nowiki>