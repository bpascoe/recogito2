require.config({
  baseUrl: "/assets/javascripts/",
  fileExclusionRegExp: /^lib$/,
  paths: {
    marked: '/webjars/marked/0.3.6/marked.min',
    i18n: '../vendor/i18n'
  }
});

// load text
require([
  'document/annotation/common/baseTextApp',
  'document/annotation/text/selection/highlighter',
  'document/annotation/text/selection/selectionHandler2',
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

// load map
require([
  'common/utils/placeUtils',
  'common/annotationView',
  'common/api',
  'common/config',
  'document/map/map'
], function(PlaceUtils, AnnotationView, API, Config, Map) {

  jQuery(document).ready(function() {

    var map = new Map(jQuery('.map')),
    // var center = [-33.865143, 151.209900],
    //     map = L.map('map', { center: center, zoom: 10}),
    // var marker = L.marker(center);
    // marker.addTo(map.leafletMap)

        /** Init the map with the annotations and fetch places **/
        onAnnotationsLoaded = function(a) {
          var annotations = new AnnotationView(a);
          map.setAnnotations(annotations.readOnly());
          return API.listPlacesInDocument(Config.documentId, 0, 2000);
        },

        /** Init the map with the places **/
        onPlacesLoaded = function(response) {
          map.setPlaces(response.items);
        },

        onLoadError = function(error) {
          // TODO implement
        };

    PlaceUtils.initGazetteers().done(function() {
      API.listAnnotationsInDocument(Config.documentId)
         .then(onAnnotationsLoaded)
         .done(onPlacesLoaded)
         .fail(onLoadError);
    });

    // map from text to map
    $('span.annotation.place').on("click", function(){
      
    });
  });
});
// // way two
// require([
//   'common/utils/placeUtils',
//   'common/annotationView',
//   'common/api',
//   'common/config',
//   'document/map/map'
// ], function(PlaceUtils, AnnotationView, API, Config, Map) {

//   jQuery(document).ready(function() {

//     var mapTmp = new Map(jQuery('.map')),
//     var center = [-33.865143, 151.209900];
//     var map = L.map('map', { center: center, zoom: 10});
//         L.tileLayer('https://api.mapbox.com/styles/v1/{id}/tiles/{z}/{x}/{y}?access_token={accessToken}', {
//             attribution: 'Map data &copy; <a href="https://www.openstreetmap.org/">OpenStreetMap</a> contributors, <a href="https://creativecommons.org/licenses/by-sa/2.0/">CC-BY-SA</a>, Imagery © <a href="https://www.mapbox.com/">Mapbox</a>',
//             maxZoom: 18,
//             // zoom: 13,
//             id: 'mapbox/streets-v11',
//             accessToken: 'pk.eyJ1Ijoiem9uZ3dlbiIsImEiOiJjazM4NHZlMXkwMHd5M2RtbWltb20zd202In0.y007RQVAtF7YyklnTDW56A'
//         }).addTo(map);

//         /** Init the map with the annotations and fetch places **/
//     var onAnnotationsLoaded = function(a) {
//         var annotations = new AnnotationView(a);
//         mapTmp.setAnnotations(annotations.readOnly());
//         return API.listPlacesInDocument(Config.documentId, 0, 2000);
//       },

//       /** Init the map with the places **/
//       onPlacesLoaded = function(response) {
//         map.setPlaces(response.items);
//       },

//       onLoadError = function(error) {
//         // TODO implement
//       };

//   PlaceUtils.initGazetteers().done(function() {
//     API.listAnnotationsInDocument(Config.documentId)
//        .then(onAnnotationsLoaded)
//        .done(onPlacesLoaded)
//        .fail(onLoadError);
//   });
// });



// center = [-33.865143, 151.209900];
// var map = L.map('map', {
//     center: center,
//     zoom: 10
// });
// L.tileLayer('https://api.mapbox.com/styles/v1/{id}/tiles/{z}/{x}/{y}?access_token={accessToken}', {
//     attribution: 'Map data &copy; <a href="https://www.openstreetmap.org/">OpenStreetMap</a> contributors, <a href="https://creativecommons.org/licenses/by-sa/2.0/">CC-BY-SA</a>, Imagery © <a href="https://www.mapbox.com/">Mapbox</a>',
//     maxZoom: 18,
//     // zoom: 13,
//     id: 'mapbox/streets-v11',
//     accessToken: 'pk.eyJ1Ijoiem9uZ3dlbiIsImEiOiJjazM4NHZlMXkwMHd5M2RtbWltb20zd202In0.y007RQVAtF7YyklnTDW56A'
// }).addTo(map);
// // add a marker
// var marker = L.marker(center).addTo(map);
// marker.on("click", function(e){
//   marker.bindPopup(e.latlng).openPopup();
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


