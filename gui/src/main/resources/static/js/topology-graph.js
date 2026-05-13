(function () {
    function readNodes() {
        return Array.from(document.querySelectorAll('.graph-node-data')).map(function (el) {
            return {
                id: String(el.dataset.id || ''),
                name: String(el.dataset.name || ''),
                ip: String(el.dataset.ip || ''),
                next: String(el.dataset.next || ''),
                previous: String(el.dataset.previous || ''),
                online: String(el.dataset.online || 'true') === 'true'
            };
        }).filter(function (node) {
            return node.id.length > 0;
        });
    }

    function clearGraph(svg, layer) {
        while (svg.firstChild) svg.removeChild(svg.firstChild);
        while (layer.firstChild) layer.removeChild(layer.firstChild);
    }

    function createSvgElement(name) {
        return document.createElementNS('http://www.w3.org/2000/svg', name);
    }

    function drawArrow(svg, from, to, label) {
        var dx = to.x - from.x;
        var dy = to.y - from.y;
        var distance = Math.sqrt(dx * dx + dy * dy) || 1;
        var nodeRadius = 76;

        var startX = from.x + (dx / distance) * nodeRadius;
        var startY = from.y + (dy / distance) * 43;
        var endX = to.x - (dx / distance) * nodeRadius;
        var endY = to.y - (dy / distance) * 43;

        var line = createSvgElement('line');
        line.setAttribute('x1', startX);
        line.setAttribute('y1', startY);
        line.setAttribute('x2', endX);
        line.setAttribute('y2', endY);
        line.setAttribute('class', 'topology-edge');
        line.setAttribute('marker-end', 'url(#arrowhead)');
        svg.appendChild(line);

        var text = createSvgElement('text');
        text.setAttribute('x', (startX + endX) / 2);
        text.setAttribute('y', (startY + endY) / 2 - 8);
        text.setAttribute('text-anchor', 'middle');
        text.setAttribute('class', 'topology-edge-label');
        text.textContent = label;
        svg.appendChild(text);
    }

    function drawSelfLoop(svg, node) {
        var path = createSvgElement('path');
        var x = node.x;
        var y = node.y;
        var d = 'M ' + (x + 58) + ' ' + (y - 42) + ' C ' + (x + 145) + ' ' + (y - 105) + ', ' + (x + 145) + ' ' + (y + 105) + ', ' + (x + 58) + ' ' + (y + 42);
        path.setAttribute('d', d);
        path.setAttribute('class', 'topology-edge');
        path.setAttribute('marker-end', 'url(#arrowhead)');
        svg.appendChild(path);
    }

    function addArrowMarker(svg) {
        var defs = createSvgElement('defs');
        var marker = createSvgElement('marker');
        marker.setAttribute('id', 'arrowhead');
        marker.setAttribute('markerWidth', '10');
        marker.setAttribute('markerHeight', '7');
        marker.setAttribute('refX', '9');
        marker.setAttribute('refY', '3.5');
        marker.setAttribute('orient', 'auto');

        var polygon = createSvgElement('polygon');
        polygon.setAttribute('points', '0 0, 10 3.5, 0 7');
        polygon.setAttribute('fill', '#002e65');

        marker.appendChild(polygon);
        defs.appendChild(marker);
        svg.appendChild(defs);
    }

    function render() {
        var graph = document.getElementById('topologyGraph');
        var svg = document.getElementById('topologySvg');
        var layer = document.getElementById('topologyNodesLayer');

        if (!graph || !svg || !layer) return;

        var nodes = readNodes();
        clearGraph(svg, layer);
        addArrowMarker(svg);

        if (nodes.length === 0) return;

        var rect = graph.getBoundingClientRect();
        var width = rect.width || graph.clientWidth || 800;
        var height = rect.height || graph.clientHeight || 430;
        var centerX = width / 2;
        var centerY = height / 2;
        var radius = Math.max(110, Math.min(width, height) / 2 - 95);

        var positions = {};
        var sortedNodes = nodes.slice().sort(function (a, b) {
            return Number(a.id) - Number(b.id);
        });

        sortedNodes.forEach(function (node, index) {
            var angle = -Math.PI / 2 + (2 * Math.PI * index / sortedNodes.length);
            positions[node.id] = {
                x: centerX + radius * Math.cos(angle),
                y: centerY + radius * Math.sin(angle),
                node: node
            };
        });

        sortedNodes.forEach(function (node) {
            var from = positions[node.id];
            var to = positions[node.next];

            if (!from) return;

            if (node.next === node.id) {
                drawSelfLoop(svg, from);
            } else if (to) {
                drawArrow(svg, from, to, 'next');
            }
        });

        sortedNodes.forEach(function (node) {
            var pos = positions[node.id];
            var div = document.createElement('div');
            div.className = 'topology-node' + (node.online ? '' : ' offline');
            div.style.left = pos.x + 'px';
            div.style.top = pos.y + 'px';
            div.innerHTML = '' +
                '<span class="topology-node-name">' + escapeHtml(node.name) + '</span>' +
                '<span class="topology-node-id">ID: ' + escapeHtml(node.id) + '</span>' +
                '<span class="topology-node-next">next: ' + escapeHtml(node.next || '-') + '</span>';
            layer.appendChild(div);
        });
    }

    function escapeHtml(value) {
        return String(value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
    }

    window.addEventListener('load', render);
    window.addEventListener('resize', render);
})();
