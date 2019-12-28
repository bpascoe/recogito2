define(['common/hasEvents'], function(HasEvents) {

  var LayerSwitcher = function() {

    var self = this,

        element = jQuery(
          '<div class="clicktrap">' +
            '<div class="modal-wrapper">' +
              '<div class="modal layerswitcher">' +
                '<div class="modal-header">' +
                  '<h2>Base Maps</h2>' +
                  '<button class="nostyle outline-icon cancel">&#xe897;</button>' +
                '</div>' +
                '<div class="modal-body">' +
                  '<ul>' +
                    '<li data-name="AWMC">' +
                      '<div class="thumb-container"><img class="map-thumb" src="http://a.tiles.mapbox.com/v3/isawnyu.map-knmctlkh/7/68/47.png"></div>' +
                      '<h3>Empty Basemap</h3>' +
                      '<p><a href="http://awmc.unc.edu/wordpress/tiles/" target="_blank">Geographically accurate basemap of the ancient world by the Ancient World Mapping Center</a>, ' +
                      'University of North Carolina at Chapel Hill.</p>' +
                    '</li>' +

                    // '<li data-name="STREET">' +
                    //   '<div class="thumb-container"><img class="map-thumb" src="https://api.mapbox.com/styles/v1/mapbox/streets-v11/static/151.4241,-33.78,4,0,0/120x120@2x?access_token=tk.eyJ1Ijoiem9uZ3dlbiIsImV4cCI6MTU3NzQ5MDk4NiwiaWF0IjoxNTc3NDg3Mzg1LCJzY29wZXMiOlsiZXNzZW50aWFscyIsInNjb3BlczpsaXN0IiwibWFwOnJlYWQiLCJtYXA6d3JpdGUiLCJ1c2VyOnJlYWQiLCJ1c2VyOndyaXRlIiwidXBsb2FkczpyZWFkIiwidXBsb2FkczpsaXN0IiwidXBsb2Fkczp3cml0ZSIsInN0eWxlczp0aWxlcyIsInN0eWxlczpyZWFkIiwiZm9udHM6bGlzdCIsImZvbnRzOnJlYWQiLCJmb250czp3cml0ZSIsInN0eWxlczp3cml0ZSIsInN0eWxlczpsaXN0IiwidG9rZW5zOnJlYWQiLCJ0b2tlbnM6d3JpdGUiLCJkYXRhc2V0czpsaXN0IiwiZGF0YXNldHM6cmVhZCIsImRhdGFzZXRzOndyaXRlIiwidGlsZXNldHM6bGlzdCIsInRpbGVzZXRzOnJlYWQiLCJ0aWxlc2V0czp3cml0ZSIsInZpc2lvbjpyZWFkIiwidmlzaW9uOmRvd25sb2FkIiwic3R5bGVzOmRyYWZ0IiwiZm9udHM6bWV0YWRhdGEiLCJkYXRhc2V0czpzdHVkaW8iLCJjdXN0b21lcnM6d3JpdGUiLCJjcmVkZW50aWFsczpyZWFkIiwiY3JlZGVudGlhbHM6d3JpdGUiLCJhbmFseXRpY3M6cmVhZCJdLCJjbGllbnQiOiJtYXBib3guY29tIiwibGwiOjE1NzYxMDIwNTAwOTEsIml1IjpudWxsLCJlbWFpbCI6Inpvbmd3ZW4uZmFuQHVvbi5lZHUuYXUifQ.4wiNg3xSnQ1NwAd5LF-cNg&logo=false&attribution=false&fresh=false%22"></div>' +
                    //   '<h3>Base street Map</h3>' +
                    //   '<p>Mapbox streets via <a href="https://www.openstreetmap.org" target="_blank">OpenStreetMap2</a>.</p>' +
                    // '</li>' +

                    '<li data-name="DARE">' +
                      '<div class="thumb-container"><img class="map-thumb" src="http://dare.ht.lu.se/tiles/imperium/7/68/47.png"></div>' +
                      '<h3>Ancient Places</h3>' +
                      '<p>Roman Empire base map by the <a href="http://dare.ht.lu.se/" target="_blank">Digital Atlas of the Roman Empire</a>, Lund University, Sweden.</p>' +
                    '</li>' +

                    '<li data-name="OSM">' +
                      '<div class="thumb-container"><img class="map-thumb" src="http://a.tile.openstreetmap.org/7/68/47.png"></div>' +
                      '<h3>Modern Places</h3>' +
                      '<p>Modern places and roads via <a href="http://www.openstreetmap.org" target="_blank">OpenStreetMap</a>.</p>' +
                    '</li>' +

                    '<li data-name="AERIAL">' +
                      '<div class="thumb-container"><img class="map-thumb" src="http://api.tiles.mapbox.com/v4/mapbox.satellite/7/68/47.png?access_token=pk.eyJ1IjoicGVsYWdpb3MiLCJhIjoiMWRlODMzM2NkZWU3YzkxOGJkMDFiMmFiYjk3NWZkMmUifQ.cyqpSZvhsvBGEBwRfniVrg"></div>' +
                      '<h3>Aerial</h3>' +
                      '<p>Aerial imagery via <a href="https://www.mapbox.com/" target="_blank">Mapbox</a>.</p>' +
                    '</li>' +
                  '</ul>' +
                '</div>' +
              '</div>' +
            '</div>' +
          '</div>'),

        btnCancel = element.find('.cancel'),

        open = function() {
          element.show();
        },

        close = function() {
          element.hide();
        };

    element.hide();
    jQuery(document.body).append(element);

    btnCancel.click(close);

    element.on('click', 'li', function(e) {
      var target = jQuery(e.target),
          li = target.closest('li'),
          layerName = li.data('name');

      self.fireEvent('changeLayer', layerName);
      close();
    });

    this.open = open;

    HasEvents.apply(this);
  };
  LayerSwitcher.prototype = Object.create(HasEvents.prototype);

  return LayerSwitcher;

});
