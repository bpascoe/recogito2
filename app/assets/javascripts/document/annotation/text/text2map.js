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
marker.click(function(e){
  marker.bindPopup(e.latlng).openPopup();
});

// map from text to map
$(".place").on("click", function(){
  
});


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



// from https://c21ch.newcastle.edu.au/textmaptext/castieaudiaries/map.php?name=1856
//Geoid is the id from clavins gazetteer
//key-value, geoid-coords{lat, lng} 2d array
var placeNamesGeo = []
//key-value, textname-geoid-foundtext 3d array
var placeNamesReferences = [];
//key-value, geoid-infowindow. For easier access to infowindows
var placeNamesInfoWindows = [];
//key-value, geoid-placemarks
var placeNamesMarkers = [];
//key-value, geoid-texname-boolean (used to see if each geoid is currently used by atleast one text)
var activeMarkers = [];
//Google map object
var map;
var openwindow;
var geoXml;

// opening/closing accordion panel
var acc = document.getElementsByClassName("accordion");
for (var i = 0; i < acc.length; i++) {
  acc[i].onclick = function() {
    this.classList.toggle("active");
    var panel = this.nextElementSibling;
    if (panel.style.display === "block") {
      panel.style.display = "none";
    } else {
      panel.style.display = "block";
    }
  }
}

$(document).on('submit', '#add_text_form', function(e) {
  addtotextlist($("input#text_typeahead").val());
  e.preventDefault();
});

window.onload = function() {
  //Setup kml parser
  geoXml = new geoXML3.parser({
    singleInfoWindow: false,
    afterParse: geoXmlPost
  });

  //get params from query string
  var queryparam = getUrlVars();
  var split = [];
  if (queryparam.hasOwnProperty('name')) {
    split = queryparam['name'].split(",");
  }
  for (var i = 0; i < split.length; i++) {
    addtotextlist(split[i]);
  }

  //Get each tablink, add switchtab onclick
  $("#tabselector").change(switchtab);

}

//Proccess the retrived KML documents to generate placemarks and infowindows
function geoXmlPost(doc) {
  //Turn them into infomarkers
  var geoXmlDoc = doc[0];
  var textname = geoXmlDoc["url"].split('/').pop().split('.')[0];
  //Get id/locations from kml
  for (marker in geoXmlDoc.placemarks) {
    placeNamesGeo[geoXmlDoc.placemarks[marker].name] = geoXmlDoc.placemarks[marker].Point.coordinates[0];
  }

  //geoxml3 doesnt grab the names...
  //also can make this much neater, turn if into !key in placenamesgeo, have the extra stuff at the start, have the shared stuff after the if
  for (var geokey in placeNamesReferences[textname]) {
    if (geokey in placeNamesGeo) {
      //Parent list of texts inwhich the place appears
      var listItemMaster = document.createElement("li");
      var unorderedList = document.createElement("ul");
      listItemMaster.innerHTML = textname;
      listItemMaster.appendChild(unorderedList);
      listItemMaster.setAttribute("data-textname", textname);
      listItemMaster.setAttribute("data-locationkey", geokey);
      //TODO factor out repition
      if (geokey in placeNamesInfoWindows) {
        //If a infowindow already exists for placename, just append to the list
        for (var j = 0; j < placeNamesReferences[textname][geokey].length; j++) {
          var listItem = document.createElement("li");
          listItem.className = "placenameListReference";
          listItem.setAttribute("data-textname", textname);
          listItem.setAttribute("data-scrolltokey", geokey);
          listItem.setAttribute("data-scrolltoindex", j);
          listItem.innerHTML = placeNamesReferences[textname][geokey][j];
          listItem.addEventListener('click', scrollto);
          unorderedList.appendChild(listItem);
        }
        //grab content, append listItemMaster
        placeNamesInfoWindows[geokey].getContent().childNodes[1].appendChild(listItemMaster);
      } else {
        // If a infowindow doesnt exists for placename, make an info window, the parent list and the list of occurences in text
        var div = document.createElement("div");
        var unorderedListMaster = document.createElement("ul");
        var preferedName = document.getElementsByClassName(geokey)[0].getAttribute("data-preferedName");
        var p = document.createElement("p");
        p.innerHTML = preferedName;
        div.appendChild(p);
        div.appendChild(unorderedListMaster);
        unorderedListMaster.appendChild(listItemMaster)
        for (var j = 0; j < placeNamesReferences[textname][geokey].length; j++) {
          var listItem = document.createElement("li");
          listItem.className = "placenameListReference";
          listItem.setAttribute("data-textname", textname);
          listItem.setAttribute("data-scrolltokey", geokey);
          listItem.setAttribute("data-scrolltoindex", j);
          listItem.innerHTML = placeNamesReferences[textname][geokey][j];
          listItem.addEventListener('click', scrollto);
          unorderedList.appendChild(listItem);
        }
        placeNamesInfoWindows[geokey] = new google.maps.InfoWindow({
          content: div
        });
        placeNamesMarkers[geokey] = new google.maps.Marker({
          position: {
            lat: placeNamesGeo[geokey]["lat"],
            lng: placeNamesGeo[geokey]["lng"]
          },
          map: map,
          title: geokey
        });
      }
      if (!(geokey in activeMarkers)) {
        activeMarkers[geokey] = [];
      }
      activeMarkers[geokey][textname] = true;
      placeNamesMarkers[geokey].addListener('click', function() {
        //Close the last window
        /*
                        if(typeof(openwindow) != "undefined"){
                            openwindow.close();
                        }
                        console.log(placeNamesInfoWindows[this.title]);
                        if(placeNamesInfoWindows[this.title] != openwindow){
                            openwindow = placeNamesInfoWindows[this.title];
                            openwindow.open(map, placeNamesMarkers[this.title]);
                        }else{
                            openwindow = undefined;
                        }*/


        for (infowindow in placeNamesInfoWindows) {
          placeNamesInfoWindows[infowindow].close();
        }

        placeNamesInfoWindows[this.title].open(map, placeNamesMarkers[this.title]);

      });
    }
  }
  var firststop = document.getElementsByClassName("placename")[0];
  gotocoordplacename(firststop.classList[1]);
}

function initMap() {
  map = new google.maps.Map(document.getElementById('map'), {
    zoom: 9,
    center: {
      lat: -23.116667,
      lng: 132.133333
    },
    streetViewControl: true,
  });
}

function toggleactivemarkers() {
  var textname = this.getAttribute("data-name");

  //Change active markers array, if one element is true, keep it, if all false hide it
  //For every placemarker, check if it is referenced by atleast one active text and show it. If not hide it.
  for (var key in placeNamesReferences[textname]) {
    activeMarkers[key][textname] = this.checked;
    for (var text in activeMarkers[key]) {
      if (activeMarkers[key][text]) {
        //Googles way to put a marker on the map
        placeNamesMarkers[key].setMap(map);
        break;
      }
      placeNamesInfoWindows[key].close();
      placeNamesMarkers[key].setMap(null);
    }
  }

  //toggle lists in infowindows
  for (var infowindow in placeNamesInfoWindows) {
    var elements = placeNamesInfoWindows[infowindow].getContent().childNodes[1].childNodes;
    for (var j = 0; j < elements.length; j++) {
      if (elements[j].getAttribute("data-textname") == textname) {
        if (this.checked) {
          elements[j].style.display = "block";
        } else {
          elements[j].style.display = "none";
        }
      }
    }
  }
}

function scrollto() {
  var key = this.getAttribute("data-scrolltokey");
  var index = this.getAttribute("data-scrolltoindex");
  var textname = this.getAttribute("data-textname");
  var textcontent = document.getElementById(textname);
  var elements = textcontent.getElementsByClassName(key);
  $("#tabselector").val(textname);
  switchtabhelper();
  elements[index].scrollIntoView({
    behavior: "smooth"
  })
}

function gotocoordplacename(placename) {
  if (placename in placeNamesGeo) {
    var lat = placeNamesGeo[placename]["lat"];
    var long = placeNamesGeo[placename]["lng"];
    var lll = new google.maps.LatLng(lat, long);
    if (typeof(openwindow) != "undefined") {
      if (openwindow != placeNamesInfoWindows[placename]) {
        openwindow.close();
        openwindow = undefined;
      }
    }
    map.panTo(lll);
  } else {
    console.log(placename + " not in list")
  }
}

function gotocoordclick() {
  var classList = this.classList;
  if (classList[0] == "placename") {
    gotocoordplacename(classList[1])
  } else {
    console.log("function called on non placename")
  }
}

function switchtab() {
  switchtabhelper(this);
}

function switchtabhelper() {
  // var i, tabcontent, tablinks;
  // Get all elements with class="tabcontent" and hide them
  tabcontent = $(".tabcontent");
  for (i = 0; i < tabcontent.length; i++) {
    tabcontent[i].style.display = "none";
  }

  // Get all elements with class="tablinks" and remove the class "active"
  // tablinks = document.getElementsByClassName("tablinks");
  // for (i = 0; i < tablinks.length; i++) {
  //     tablinks[i].className = tablinks[i].className.replace(" active", "");
  // }

  // Show the current tab, and add an "active" class to the button that opened the tab
  $("#" + $("#tabselector").val()).show();
}

function addtotextlist(text) {
  //check if exists in tab content
  var tabcontent = $(".tabcontent");
  /*TODO X to remove from the list.
  for (var i = 0; i < tabcontent.length; i++) {
    if (tabcontent[i].id === text) {
      if (tabcontent[i].getAttribute("in-list") == "false") {
        tabcontent[i].setAttribute("in-list", true);
        //TODO
        //toggle markers
        //toggle hidden in drop box
        //show list item
      }
      return;
    }
  }*/
  //If its not already in the list, retrive it.
  $.ajax({
    //Get html page.
    url: 'mapped_texts/' + text + ".html",
    //If html is found
    success: function(data) {
      //Create a new div for the tabcontent, append to texts
      var entry = document.createElement('div');
      entry.classList.add("tabcontent");
      entry.id = text;
      entry.innerHTML = data;
      entry.setAttribute("in-list", true)
      $("#texts").append(entry);

      //TODO Remove loop over tabs (as we add them one at a time now)
      //Build placenames array which will be used to match markers to <a> tags in the text
      var tabs = document.getElementsByClassName("tabcontent");
      for (var i = 0; i < tabs.length; i++) {
        var textname = tabs[i].getAttribute("id");
        if (textname == text) {
          if (!(textname in placeNamesReferences)) {
            placeNamesReferences[textname] = [];
          }
          var placeNames = tabs[i].getElementsByClassName("placename");
          for (var j = 0; j < placeNames.length; j++) {
            var key = placeNames[j].classList[1];
            if (!(key in placeNamesReferences[textname])) {
              placeNamesReferences[textname][key] = [];
            }
            //add goto coordinates onclick to each tag
            placeNamesReferences[textname][key].push(placeNames[j].innerHTML);
            placeNames[j].onclick = gotocoordclick;
          }
        }
      }
      //create list element
      var entry = document.createElement('li');
      entry.appendChild(document.createTextNode(text));

      //create checkbox element
      var checkbox = document.createElement('input');
      $(checkbox).attr({
          type: "checkbox",
          checked: true,
          "data-name": text
        })
        .addClass("checkboxes").on("change", toggleactivemarkers);
      entry.appendChild(checkbox);

      //TODO append remove button
      var removebutton = document.createElement('button');
      $(removebutton).attr({
        "data-name": text
      }).text("x").on("click", removefromtextlist);
      // entry.appendChild(removebutton);

      $("#text_list").append(entry);

      //parse kml
      geoXml.parse('https://' + window.location.hostname + '/textmaptext/castieaudiaries/mapped_texts/' + text + '.kml');

      //add a tabselector
      $('#tabselector').append($('<option>', {
        value: text,
        text: text
      }));

      //Because each text is added async, calling switch tab hides the most recently added.
      switchtabhelper();
    }
  });

}

function removefromtextlist() {
  var name = this.getAttribute("data-name");
  //tab content attribute in-list false;
  $("div#" + name).attr("in-list", false);
  //Hide text

  //Hide list item
  var tabselector = $("#tabselector option");
  for (var i = 0; i < tabselector.length; i++) {
    if (name == tabselector[i].value) {
      tabselector[i].setAttribute("hidden", true);
    }
  }
  //Hide select option
  //Turn off active markers
  //if check, toggleactivemarkers
  //if not dont
}

function getUrlVars() {
  var vars = [],
    hash;
  if (window.location.href.indexOf('?') > 0) {
    var hashes = window.location.href.slice(window.location.href.indexOf('?') + 1).split('&');
    for (var i = 0; i < hashes.length; i++) {
      hash = hashes[i].split('=');
      vars.push(hash[0]);
      console.log(i);
      console.log(hash[0]);
      vars[hash[0]] = hash[1].replace("%20", "_");
    }
  }
  return vars;
}
