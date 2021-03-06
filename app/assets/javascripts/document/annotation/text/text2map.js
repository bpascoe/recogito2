require.config({
  baseUrl: "/assets/javascripts/",
  fileExclusionRegExp: /^lib$/,
  paths: {
    marked: '/webjars/marked/0.3.6/marked.min',
    i18n: '../vendor/i18n'
  }
});

require([
// load text
  'document/annotation/common/baseTextApp',
  'document/annotation/text/selection/highlighter',
  'document/annotation/text/selection/selectionHandler2',
  'document/annotation/text/selection/phraseAnnotator',

// load map
  'common/utils/placeUtils',
  'common/annotationView',
  'common/api',
  'common/config',
  'document/map/map2'
], function(BaseTextApp, Highlighter, SelectionHandler, PhraseAnnotator, PlaceUtils, AnnotationView, API, Config, Map) {

  jQuery(document).ready(function() {
// load text
    var contentNode = document.getElementById('content'),
        highlighter = new Highlighter(contentNode),
        selector = new SelectionHandler(contentNode, highlighter),
        phraseAnnotator = new PhraseAnnotator(contentNode, highlighter);

    new BaseTextApp(contentNode, highlighter, selector, phraseAnnotator);
    // process the whole corpus
    var folderId = sessionStorage.getItem("folderId");
    if (folderId)
      $.get( document.location.origin + "/api/directory/my/" + folderId, function(data) {
        var items = data.items;
        if (items.length > 1)
          $.each(items,function(index,item){
            var docId =item.id;
            if (docId != document.location.pathname.split('/')[2]) 
              $.get( document.location.origin + "/document/"+docId+"/part/1/edit", function(data) {
                var lis = $($(data).find('ul.menu')[1]).find('li')
                lis.removeClass('active');
                lis.find('a').removeAttr('onclick');
                $('.sidebar ul.menu').append(lis);
              });
              // var lis = $('.sidebar ul.menu').find('li.active')
              // lis.removeAttr('class');
              // lis.find('a').removeAttr('onclick');
              // lis.find('a').attr('href') = document.location.origin + "/document/"+docId+"/part/1/edit";
              // $('.sidebar ul.menu').append(lis);
          });
          
      });
// load map
    var map = new Map(jQuery('.map')),
    // var center = [-33.865143, 151.209900],
    //     map = L.map('map', { center: center, zoom: 10}),
    // var marker = L.marker(center);
    // marker.addTo(map.leafletMap)

        /** Init the map with the annotations and fetch places **/
        getAnnotations = function ( folderId ) {
         var result = [];
         $.ajax({url:document.location.origin + "/api/directory/my/" + folderId, type: 'get',async: false,success: function(data) {
              var items = data.items;
              if (items.length > 1)
                $.each(items,function(index,item){
                  var docId =item.id;
                  // if (docId != Config.documentId) 
                    $.ajax( {url:document.location.origin + "/api/document/" + docId+"/annotations",
                           type: 'get',async: false,success: function(data) {
                      result = result.concat(data);
                      }
                    });
                  });
                }
              });
         return result;
        },
        onAnnotationsLoaded = function(a) {
          // if (folderId) a = a.concat(getAnnotations( folderId));
          if (folderId) a = getAnnotations( folderId);
          var annotations = new AnnotationView(a);
          map.setAnnotations(annotations.readOnly());
          return API.listPlacesInDocument(Config.documentId, 0, 2000);
        },

        /** Init the map with the places **/
        onPlacesLoaded = function(response) {
          map.setPlaces(response.items);
          // Mapping map location to text location
          // annotation-id or filepart-id
          $('span.annotation.place').each(function() {
            $(this).attr("id", $(this).attr("data-id"));
          });
          // replace urls in the file list to our own place
          var pattern = /(\/document\/[a-zA-Z0-9]+)\/part(\/[0-9]+)\/edit/;
          $('.sidebar>.menu>li a').each(function(index,value){
            url = $(this).attr('href');
            if (url != "#") {
              this.href = this.href.replace(pattern,'$1$2/text2map');
            }
          });
          // Mapping text location to map location
          $('span.annotation.place').on("click", function(event){
            // var annotation_id = event.target.annotation.annotation_id;
            // uris = response.items[0].is_conflation_of[0].uri
            // var uri = event.target.annotation.bodies[1].uri
            jQuery.each(response.items, function(index, item) {// loop to find which popup
              if (item.is_conflation_of[0].uri == event.target.annotation.bodies[1].uri) {
                latlng = response.items[index].representative_point;
                map.showCard({lng: latlng[0], lat: latlng[1]}, event.target.annotation.annotation_id); 
                var marker = L.marker([latlng[1], latlng[0]],{}).addTo(map.leafletMap);
                map.leafletMap.flyTo([latlng[1], latlng[0]], 10);//zoom = 10
                return false;
              }
            });
          });
          // url zooms to location
          partId = window.location.hash.substring(1);
          if (partId.length>2) {
            $('span[data-id=' + partId + ']').click();
          }
          // jump to text
          // $('div.popup a.jump-to-text').click(function() {
          //   $('div.popup a.jump-to-text-btn')[0].click();
          // });
          
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

    // add breadcrumbs
    var breadcrumbs = sessionStorage.getItem("breadcrumbs");
    $(".root").attr("href",window.location.origin+"#");
    JSON.parse(breadcrumbs).map(function(e) {
      $(".root").append(" &gt; <a class='folder' href="+window.location.origin+"#"+e.id+">"+e.title+"</a>");
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

