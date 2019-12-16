require.config({
  baseUrl: "/assets/javascripts/",
  fileExclusionRegExp: /^lib$/,
  paths: {
    marked: '/webjars/marked/0.3.6/marked.min',
    i18n: '../vendor/i18n'
  }
});

require([
  'document/annotation/common/baseTextApp',
  'document/annotation/text/selection/highlighter',
  'document/annotation/text/selection/selectionHandler',
  'document/annotation/text/selection/phraseAnnotator'
], function(BaseTextApp, Highlighter, SelectionHandler, PhraseAnnotator) {

  jQuery(document).ready(function() {
    var contentNode = document.getElementById('content'),
        highlighter = new Highlighter(contentNode),
        selector = new SelectionHandler(contentNode, highlighter),
        phraseAnnotator = new PhraseAnnotator(contentNode, highlighter);

    new BaseTextApp(contentNode, highlighter, selector, phraseAnnotator);
  });

});


center = [-33.865143, 151.209900]
var map = L.map('map', {
    center: center,
    zoom: 7
});
L.tileLayer('https://api.mapbox.com/styles/v1/{id}/tiles/{z}/{x}/{y}?access_token={accessToken}', {
    attribution: 'Map data &copy; <a href="https://www.openstreetmap.org/">OpenStreetMap</a> contributors, <a href="https://creativecommons.org/licenses/by-sa/2.0/">CC-BY-SA</a>, Imagery © <a href="https://www.mapbox.com/">Mapbox</a>',
    maxZoom: 18,
    // zoom: 13,
    id: 'mapbox/streets-v11',
    accessToken: 'pk.eyJ1Ijoiem9uZ3dlbiIsImEiOiJjazM4NHZlMXkwMHd5M2RtbWltb20zd202In0.y007RQVAtF7YyklnTDW56A'
}).addTo(map);
// add a marker
var marker = L.marker(center).addTo(map);
marker.bindPopup('More information here').openPopup();

// add a circle
/*var circle = L.circle(center, {
  color: 'green',
  fillColor: '#f03',
  fillOpacity: 0.5,
  radius: 10000
}).addTo(map);*/

// map.on('zoomstart', function () {
//     var zoomLevel = map.getZoom();
//     var tooltip = $('.leaflet-tooltip');

//     switch (zoomLevel) {
//         case -2:
//             tooltip.css('font-size', 7);
//             break;
//         case -1:
//             tooltip.css('font-size', 10);
//             break;
//         case 0:
//             tooltip.css('font-size', 12);
//             break;
//         case 1:
//             tooltip.css('font-size', 14);
//             break;
//         case 2:
//             tooltip.css('font-size', 16);
//             break;
//         case 3:
//             tooltip.css('font-size', 18);
//             break;
//         default:
//             tooltip.css('font-size', 14);
//     }
// });

// popups layers
// var popup = L.popup().setLatLng([35,120]).setContent('俺是一个Popup图层').openOn(map);//or .addTo(map) not close other popups;
/*var mypop = L.popup();
map.on('click', function(e) {
  var content = 'You visit this place：<br>' + e.latlng.toString();
  mypop.setLatLng(e.latlng).setContent(content).openOn(map);
});*/

// Geolocation
// map.locate({  setView: true,  maxZoom: 16});