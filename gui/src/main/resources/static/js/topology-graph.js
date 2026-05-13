(function () {
    function toNumber(value) {
        const number = Number(value);
        return Number.isFinite(number) ? number : null;
    }

    function getNodes() {
        return Array.from(document.querySelectorAll('.topology-node-data')).map((element) => ({
            id: toNumber(element.dataset.id),
            name: element.dataset.name || 'node',
            ip: element.dataset.ip || '-',
            status: element.dataset.status || 'unknown',
            online: element.dataset.online === 'true',
            previousID: toNumber(element.dataset.previousId),
            nextID: toNumber(element.dataset.nextId),
            selected: element.dataset.selected === 'true'
        })).filter((node) => node.id !== null);
    }

    function orderByRing(nodes, selectedId) {
        if (nodes.length <= 1) {
            return nodes;
        }

        const byId = new Map(nodes.map((node) => [node.id, node]));
        const start = byId.get(selectedId) || nodes.slice().sort((a, b) => a.id - b.id)[0];
        const ordered = [];
        const seen = new Set();
        let current = start;

        while (current && !seen.has(current.id)) {
            ordered.push(current);
            seen.add(current.id);
            current = byId.get(current.nextID);
        }

        nodes
            .filter((node) => !seen.has(node.id))
            .sort((a, b) => a.id - b.id)
            .forEach((node) => ordered.push(node));

        return ordered;
    }

    function nodeRadius(node, selectedId, selectedNode) {
        if (node.id === selectedId) {
            return 74;
        }

        if (selectedNode && (node.id === selectedNode.previousID || node.id === selectedNode.nextID)) {
            return 56;
        }

        return 37;
    }

    function calculatePositions(nodes, selectedId) {
        const width = 1000;
        const height = 560;
        const centerX = width / 2;
        const centerY = height / 2;
        const radius = Math.min(width, height) / 2 - 105;
        const positions = new Map();

        if (nodes.length === 1) {
            positions.set(nodes[0].id, { x: centerX, y: centerY });
            return positions;
        }

        const startAngle = -Math.PI / 2;
        const step = (Math.PI * 2) / nodes.length;

        nodes.forEach((node, index) => {
            const angle = startAngle + step * index;
            positions.set(node.id, {
                x: centerX + Math.cos(angle) * radius,
                y: centerY + Math.sin(angle) * radius
            });
        });

        return positions;
    }

    function shortenLine(start, end, startRadius, endRadius) {
        const dx = end.x - start.x;
        const dy = end.y - start.y;
        const length = Math.sqrt(dx * dx + dy * dy) || 1;
        const ux = dx / length;
        const uy = dy / length;

        return {
            start: {
                x: start.x + ux * (startRadius + 8),
                y: start.y + uy * (startRadius + 8)
            },
            end: {
                x: end.x - ux * (endRadius + 18),
                y: end.y - uy * (endRadius + 18)
            }
        };
    }

    function buildArrowPath(start, end) {
        const center = { x: 500, y: 280 };
        const mid = {
            x: (start.x + end.x) / 2,
            y: (start.y + end.y) / 2
        };

        const awayX = mid.x - center.x;
        const awayY = mid.y - center.y;
        const awayLength = Math.sqrt(awayX * awayX + awayY * awayY) || 1;
        const curveStrength = 64;
        const control = {
            x: mid.x + (awayX / awayLength) * curveStrength,
            y: mid.y + (awayY / awayLength) * curveStrength
        };

        return `M ${start.x.toFixed(2)} ${start.y.toFixed(2)} Q ${control.x.toFixed(2)} ${control.y.toFixed(2)} ${end.x.toFixed(2)} ${end.y.toFixed(2)}`;
    }

    function createSvgDefinitions(svg) {
        svg.innerHTML = '';

        const defs = document.createElementNS('http://www.w3.org/2000/svg', 'defs');
        const marker = document.createElementNS('http://www.w3.org/2000/svg', 'marker');
        marker.setAttribute('id', 'arrowHead');
        marker.setAttribute('markerWidth', '14');
        marker.setAttribute('markerHeight', '14');
        marker.setAttribute('refX', '11');
        marker.setAttribute('refY', '5');
        marker.setAttribute('orient', 'auto');
        marker.setAttribute('markerUnits', 'strokeWidth');

        const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        path.setAttribute('d', 'M 0 0 L 12 5 L 0 10 z');
        path.setAttribute('fill', getComputedStyle(document.documentElement).getPropertyValue('--main-color') || '#b10097');

        marker.appendChild(path);
        defs.appendChild(marker);
        svg.appendChild(defs);
    }

    function renderArrows(svg, nodes, positions, selectedId, selectedNode) {
        const byId = new Map(nodes.map((node) => [node.id, node]));

        nodes.forEach((node) => {
            const target = byId.get(node.nextID);
            if (!target || target.id === node.id) {
                return;
            }

            const start = positions.get(node.id);
            const end = positions.get(target.id);
            if (!start || !end) {
                return;
            }

            const shortened = shortenLine(
                start,
                end,
                nodeRadius(node, selectedId, selectedNode),
                nodeRadius(target, selectedId, selectedNode)
            );

            const arrow = document.createElementNS('http://www.w3.org/2000/svg', 'path');
            arrow.setAttribute('class', 'topology-arrow');
            arrow.setAttribute('d', buildArrowPath(shortened.start, shortened.end));
            arrow.setAttribute('marker-end', 'url(#arrowHead)');

            if (node.id === selectedId || target.id === selectedId) {
                arrow.classList.add('highlighted');
            }

            svg.appendChild(arrow);
        });
    }

    function renderNodes(layer, nodes, positions, selectedId, selectedNode) {
        layer.innerHTML = '';

        nodes.forEach((node) => {
            const position = positions.get(node.id);
            if (!position) {
                return;
            }

            const dot = document.createElement('button');
            dot.type = 'button';
            dot.className = 'topology-dot';
            dot.style.left = `${(position.x / 1000) * 100}%`;
            dot.style.top = `${(position.y / 560) * 100}%`;
            dot.setAttribute('title', `${node.name} (${node.id})`);

            if (node.id === selectedId) {
                dot.classList.add('selected');
            } else if (selectedNode && (node.id === selectedNode.previousID || node.id === selectedNode.nextID)) {
                dot.classList.add('neighbour');
            }

            dot.innerHTML = `<span class="dot-name"></span><span class="dot-id"></span>`;
            dot.querySelector('.dot-name').textContent = node.name;
            dot.querySelector('.dot-id').textContent = `ID ${node.id}`;

            dot.addEventListener('click', () => {
                window.location.href = `/dashboard?selectedId=${encodeURIComponent(node.id)}#nodes`;
            });

            layer.appendChild(dot);
        });
    }

    function initTopology() {
        const container = document.getElementById('interactiveTopology');
        const svg = document.getElementById('topologySvg');
        const layer = document.getElementById('topologyNodesLayer');

        if (!container || !svg || !layer) {
            return;
        }

        const nodes = getNodes();
        if (nodes.length === 0) {
            return;
        }

        let selectedId = toNumber(container.dataset.selectedId);
        if (selectedId === null) {
            const markedSelected = nodes.find((node) => node.selected);
            selectedId = markedSelected ? markedSelected.id : nodes[0].id;
        }

        const orderedNodes = orderByRing(nodes, selectedId);
        const selectedNode = nodes.find((node) => node.id === selectedId) || orderedNodes[0];
        const positions = calculatePositions(orderedNodes, selectedId);

        createSvgDefinitions(svg);
        renderArrows(svg, nodes, positions, selectedId, selectedNode);
        renderNodes(layer, nodes, positions, selectedId, selectedNode);
    }

    document.addEventListener('DOMContentLoaded', initTopology);
})();
