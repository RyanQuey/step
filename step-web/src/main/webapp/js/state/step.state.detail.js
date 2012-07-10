step.state.detail = {
            store : function(level) {
                $.cookie("detailLevel", level);
            },

            get : function() {
                var level = $.cookie("detailLevel");
                if (level != null || level == "") {
                    return parseInt(level);
                } else {
                    $.cookie("detailLevel", 0);
                }
            },
            
            restore: function() {
                step.menu.tickMenuItem($("li[menu-name = 'VIEW'] ul li a").get(this.get()));
            }
};